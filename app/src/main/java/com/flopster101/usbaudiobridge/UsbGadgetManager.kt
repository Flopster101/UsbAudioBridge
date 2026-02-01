package com.flopster101.usbaudiobridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * Represents the current status of the USB gadget subsystem.
 */
data class GadgetStatus(
    val udcController: String,      // The UDC controller name (e.g., "13600000.dwc3") or "None"
    val activeFunctions: List<String>,  // List of active function names (e.g., ["uac2", "ffs.adb"])
    val isBound: Boolean            // Whether the gadget is bound to a controller
)

object UsbGadgetManager {
    private const val TAG = "UsbGadgetManager"
    
    private const val GADGET_ROOT = "/config/usb_gadget/g1"
    private const val CH_MASK = 3
    private const val SAMPLE_SIZE = 2
    
    // Explicit Identity to force Windows Re-enumeration
    private const val VENDOR_ID = "0x1d6b" // Linux Foundation
    private const val PRODUCT_ID = "0x0104" // Multifunction Composite Gadget
    
    private const val MANUFACTURER = "FloppyKernel Project"
    private const val PRODUCT = "USB Audio Bridge"
    
    // Mutex to prevent concurrent gadget operations
    private val gadgetMutex = Mutex()

    /**
     * Check if root access is granted.
     */
    fun isRootGranted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if ADB is currently active by checking the USB config property.
     */
    private fun isAdbCurrentlyActive(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("getprop sys.usb.config")
            val config = p.inputStream.bufferedReader().readText().trim()
            config.contains("adb")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ADB status: ${e.message}")
            false
        }
    }

    private suspend fun findRunningUsbHalService(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "vendor.usb-gadget-hal-1-0",
            "android.hardware.usb.gadget-service.samsung",
            "android.hardware.usb.gadget-service.mediatek",
            "android.hardware.usb-service.mediatek",
            "vendor.usb-hal-1-0",
            "vendor.usb-gadget-hal",
            "usbgadget-hal-1-0"
        )
        
        for (name in candidates) {
            try {
                val p = Runtime.getRuntime().exec("getprop init.svc.$name")
                val status = p.inputStream.bufferedReader().readText().trim()
                if (status == "running" || status == "restarting") {
                    return@withContext name
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return@withContext null
    }

    private fun configureMtkMode(udcName: String, enable: Boolean, logCallback: (String) -> Unit) {
        val value = if (enable) "1" else "0"
        val paths = listOf(
            "/sys/class/udc/$udcName/device/mode",
            "/sys/class/udc/$udcName/device/cmode"
        )
        
        for (path in paths) {
            if (runRootCommand("test -f $path", {})) {
                logCallback("[Gadget] Setting MTK specific mode: $path -> $value")
                runRootCommand("echo '$value' > $path", {})
            }
        }
    }

    private suspend fun stopUsbHal(logCallback: (String) -> Unit, settingsRepo: SettingsRepository?) {
        val serviceName = findRunningUsbHalService()
        if (serviceName != null) {
            logCallback("[Gadget] Stopping conflicting USB HAL service: $serviceName")
            // Use setprop ctl.stop to stop the service
            if (runRootCommands(listOf("setprop ctl.stop $serviceName"), {})) {
                settingsRepo?.saveStoppedHalService(serviceName)
                Thread.sleep(500) // Give it time to stop and release resources
            } else {
                logCallback("[Gadget] Failed to stop service $serviceName")
            }
        }
    }

    private suspend fun startUsbHal(logCallback: (String) -> Unit, settingsRepo: SettingsRepository?) {
        val serviceName = settingsRepo?.getStoppedHalService()
        if (serviceName != null) {
            logCallback("[Gadget] Restarting USB HAL service: $serviceName")
            runRootCommands(listOf("setprop ctl.start $serviceName"), {})
            settingsRepo.clearStoppedHalService()
        }
    }
    
    /**
     * Check if the ffs.adb function exists in configfs.
     */
    private fun isFfsAdbFunctionAvailable(): Boolean {
        return runRootCommand("test -d $GADGET_ROOT/functions/ffs.adb", {})
    }
    
    /**
     * Restore USB config to system control.
     * This triggers the system (HAL or init.rc) to reconfigure USB.
     */
    private fun restoreUsbConfig(config: String, logCallback: (String) -> Unit) {
        // Set both properties to ensure system picks it up
        runRootCommands(listOf(
            "setprop sys.usb.config $config"
        ), logCallback)
    }

    /**
     * Soft unbind - only uses direct UDC writes, preserves ffs.adb state.
     * Returns true if unbind succeeded without needing system property changes.
     * 
     * This is the only safe way to unbind if we want to preserve ADB, because
     * setting sys.usb.config=none triggers init.rc to stop adbd.
     */
    private suspend fun softUnbind(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val initialUdc = getUdcContent()
        logCallback("[Gadget] Soft unbind starting (current UDC='$initialUdc')...")
        
        if (initialUdc.isEmpty() || initialUdc == "none" || initialUdc.isBlank()) {
            logCallback("[Gadget] UDC already unbound.")
            return@withContext true
        }
        
        // IMPORTANT: On some devices (Samsung), UDC file needs chmod 666 before we can write to it
        Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 $GADGET_ROOT/UDC")).waitFor()
        
        // Try to unbind by writing empty to UDC
        Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '' > $GADGET_ROOT/UDC")).waitFor()
        
        // Check if unbind succeeded
        for (i in 1..8) {
            Thread.sleep(400)
            val currentUdc = getUdcContent()
            if (currentUdc.isEmpty() || currentUdc == "none" || currentUdc.isBlank()) {
                logCallback("[Gadget] Soft unbind successful.")
                return@withContext true
            }
        }
        
        // Try writing "none" explicitly
        Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 'none' > $GADGET_ROOT/UDC")).waitFor()
        
        for (i in 1..4) {
            Thread.sleep(400)
            val currentUdc = getUdcContent()
            if (currentUdc.isEmpty() || currentUdc == "none" || currentUdc.isBlank()) {
                logCallback("[Gadget] Soft unbind successful.")
                return@withContext true
            }
        }

        val finalUdc = getUdcContent()
        logCallback("[Gadget] Soft unbind failed - UDC='$finalUdc' (system is rebinding immediately)")
        return@withContext false
    }
    
    /**
     * Hard unbind - uses system properties which trigger HAL/init reconfiguration.
     * This WILL disrupt ADB because init.rc stops adbd when sys.usb.config=none.
     */
    private suspend fun hardUnbind(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Hard unbind (sys.usb.config=none)...")
        runRootCommands(listOf("setprop sys.usb.config none")) {}

        for (i in 1..10) {
             Thread.sleep(500)
             if (checkUdcReleased()) {
                 logCallback("[Gadget] Hard unbind successful.")
                 return@withContext true
             }
        }

        val udc = getUdcContent()
        logCallback("[Gadget] Hard unbind failed. UDC='$udc'")
        return@withContext false
    }

    private fun checkUdcReleased(): Boolean {
        return try {
            val udc = getUdcContent()
            udc.isEmpty() || udc == "none" || udc.isBlank()
        } catch(e: Exception) { false }
    }
    
    private fun getUdcContent(): String {
         val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
         return p.inputStream.bufferedReader().readText().trim()
    }

    private fun getPidForRate(rate: Int): String {
        return when (rate) {
            48000 -> "0x0104"
            44100 -> "0x0105"
            32000 -> "0x0106"
            22050 -> "0x0107"
            88200 -> "0x0108"
            96000 -> "0x0109"
            192000 -> "0x010A"
            else -> "0x010B"
        }
    }

    private fun getBcdDeviceForRate(rate: Int): String {
        return when (rate) {
            44100 -> "0x0244"
            48000 -> "0x0248"
            88200 -> "0x0288"
            96000 -> "0x0296"
            192000 -> "0x0292"
            32000 -> "0x0232"
            22050 -> "0x0222"
            else -> "0x0200"
        }
    }

    private fun getSerialNumberForRate(rate: Int, deviceSerial: String): String {
        return "UAM-SR$rate-$deviceSerial"
    }

    suspend fun enableGadget(
        logCallback: (String) -> Unit, 
        sampleRate: Int = 48000, 
        settingsRepo: SettingsRepository? = null, 
        keepAdb: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        gadgetMutex.withLock {
            enableGadgetInternal(logCallback, sampleRate, settingsRepo, keepAdb)
        }
    }
    
    private suspend fun enableGadgetInternal(
        logCallback: (String) -> Unit, 
        sampleRate: Int = 48000, 
        settingsRepo: SettingsRepository? = null, 
        keepAdb: Boolean = false
    ): Boolean {
        logCallback("[Gadget] Configuring UAC2 gadget ($sampleRate Hz)...")
        
        // Check ADB status if user wants to keep it
        var adbWasActive = false
        var ffsAdbExists = false
        if (keepAdb) {
            adbWasActive = isAdbCurrentlyActive()
            ffsAdbExists = isFfsAdbFunctionAvailable()
            if (adbWasActive && ffsAdbExists) {
                logCallback("[Gadget] ADB is active, will attempt to preserve it.")
            } else if (adbWasActive && !ffsAdbExists) {
                logCallback("[Gadget] ADB active but ffs.adb function not found.")
                adbWasActive = false
            } else {
                logCallback("[Gadget] ADB not currently active, ignoring keepAdb option.")
            }
        }
        
        // Anti-Interference: Stop USB HAL if running
        stopUsbHal(logCallback, settingsRepo)
        
        var deviceSerial = "UNKNOWN"

        // Backup original strings if not already done
        if (settingsRepo != null) {
            val (savedMan, savedProd, savedSerial) = settingsRepo.getOriginalIdentity()
            if (savedMan == null || savedProd == null || savedSerial == null) {
                try {
                    val pProd = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/product"))
                    val currProd = pProd.inputStream.bufferedReader().readText().trim()
                    
                    val pMan = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/manufacturer"))
                    val currMan = pMan.inputStream.bufferedReader().readText().trim()

                    val pSerial = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/serialnumber"))
                    val currSerial = pSerial.inputStream.bufferedReader().readText().trim()
                    
                    if (currProd.isNotEmpty() && !currProd.contains("Audio Bridge") && !currMan.contains("FloppyKernel")) {
                        settingsRepo.saveOriginalIdentity(currMan, currProd, currSerial)
                        deviceSerial = currSerial
                        logCallback("[Gadget] Backed up original identity: $currMan - $currProd ($currSerial)")
                    } else {
                        val pModel = Runtime.getRuntime().exec("getprop ro.product.model")
                        val fallbackProd = pModel.inputStream.bufferedReader().readText().trim()
                        val pBrand = Runtime.getRuntime().exec("getprop ro.product.manufacturer")
                        val fallbackMan = pBrand.inputStream.bufferedReader().readText().trim()
                        
                        // Try getting serial via su since app user likely can't read it
                        val pSer = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop ro.serialno"))
                        val fallbackSerial = pSer.inputStream.bufferedReader().readText().trim()
                        
                        settingsRepo.saveOriginalIdentity(fallbackMan, fallbackProd, fallbackSerial)
                        deviceSerial = fallbackSerial
                        logCallback("[Gadget] Backed up identity from props: $fallbackMan - $fallbackProd ($fallbackSerial)")
                    }
                } catch (e: Exception) {
                    logCallback("[Gadget] Failed to backup strings: ${e.message}")
                }
            } else {
                deviceSerial = savedSerial
            }
        }
        
        val bcdDevice = getBcdDeviceForRate(sampleRate)
        val pid = getPidForRate(sampleRate)
        val serial = getSerialNumberForRate(sampleRate, deviceSerial)
        
        val needPreserveAdb = keepAdb && adbWasActive && ffsAdbExists
        
        // Step 1: Try soft unbind first
        val softUnbindSucceeded = softUnbind(logCallback)
        
        if (!softUnbindSucceeded) {
            if (needPreserveAdb) {
                // Soft unbind failed and user wants ADB - we cannot proceed
                logCallback("[Gadget] ERROR: Cannot keep ADB enabled on this device.")
                logCallback("[Gadget] The system immediately rebinds the gadget, preventing ADB preservation.")
                logCallback("[Gadget] Please disable 'Keep ADB' option and try again.")
                return false
            }
            
            // Soft unbind failed but ADB preservation not needed - use hard unbind
            logCallback("[Gadget] Using hard unbind (ADB will be temporarily disabled)...")
            if (!hardUnbind(logCallback)) {
                logCallback("[Gadget] Aborting: Could not release USB hardware.")
                return false
            }
        }
        
        // Step 2: Ensure we're fully unbound
        if (!softUnbind(logCallback)) {
            logCallback("[Gadget] UDC already released.")
        }
        
        // Step 3: Check if we can still use ADB (only matters if soft unbind succeeded)
        // On QTI HAL devices, soft unbind works and ffs.adb remains available.
        // We only check if the function directory exists - the sys.usb.ffs.ready property
        // may be cleared during unbind but that doesn't mean ffs.adb is unusable.
        var adbAvailable = false
        if (softUnbindSucceeded && needPreserveAdb) {
            adbAvailable = isFfsAdbFunctionAvailable()
            
            if (adbAvailable) {
                logCallback("[Gadget] ffs.adb is available for composite gadget.")
            } else {
                logCallback("[Gadget] ffs.adb became unavailable, proceeding without ADB.")
            }
        }
        
        applySeLinuxPolicy(logCallback)
        
        // Step 4: Setup configfs structure
        val configCommands = mutableListOf(
            // Clear existing function links
            "rm -f $GADGET_ROOT/configs/b.1/f* || true",
            
            // Remove old UAC2 function if it exists
            "rmdir $GADGET_ROOT/functions/uac2.0 2>/dev/null || true",
            
            // Set Device Identity
            "echo \"$VENDOR_ID\" > $GADGET_ROOT/idVendor",
            "echo \"$pid\" > $GADGET_ROOT/idProduct",
            "echo \"$bcdDevice\" > $GADGET_ROOT/bcdDevice",
            "echo \"0x0200\" > $GADGET_ROOT/bcdUSB",
            
            // Create and configure UAC2 function
            "mkdir -p $GADGET_ROOT/functions/uac2.0",
            "echo $sampleRate > $GADGET_ROOT/functions/uac2.0/p_srate",
            "echo $CH_MASK > $GADGET_ROOT/functions/uac2.0/p_chmask",
            "echo $SAMPLE_SIZE > $GADGET_ROOT/functions/uac2.0/p_ssize",
            "echo $sampleRate > $GADGET_ROOT/functions/uac2.0/c_srate",
            "echo $CH_MASK > $GADGET_ROOT/functions/uac2.0/c_chmask",
            "echo $SAMPLE_SIZE > $GADGET_ROOT/functions/uac2.0/c_ssize",
            "echo 2 > $GADGET_ROOT/functions/uac2.0/req_number 2>/dev/null || true",
            
            // Set device strings
            "mkdir -p $GADGET_ROOT/strings/0x409",
            "echo \"$MANUFACTURER\" > $GADGET_ROOT/strings/0x409/manufacturer",
            "echo \"$PRODUCT\" > $GADGET_ROOT/strings/0x409/product",
            "echo \"$serial\" > $GADGET_ROOT/strings/0x409/serialnumber",
            
            // Set Configuration String
            "mkdir -p $GADGET_ROOT/configs/b.1/strings/0x409"
        )
        
        // Link functions
        if (adbAvailable) {
            configCommands.add("echo \"USB Audio + ADB\" > $GADGET_ROOT/configs/b.1/strings/0x409/configuration")
            configCommands.add("ln -s $GADGET_ROOT/functions/uac2.0 $GADGET_ROOT/configs/b.1/f1")
            configCommands.add("ln -s $GADGET_ROOT/functions/ffs.adb $GADGET_ROOT/configs/b.1/f2")
        } else {
            configCommands.add("echo \"USB Audio\" > $GADGET_ROOT/configs/b.1/strings/0x409/configuration")
            configCommands.add("ln -s $GADGET_ROOT/functions/uac2.0 $GADGET_ROOT/configs/b.1/f1")
        }
        
        if (!runRootCommands(configCommands, logCallback)) {
            logCallback("[Gadget] Failed to configure gadget structure.")
            return false
        }
        
        Thread.sleep(500)
        
        // Step 5: Bind the gadget
        if (bindGadgetWithRetry(logCallback)) {
            runRootCommands(listOf("setprop sys.usb.state uac2")) {}
            
            if (adbAvailable) {
                logCallback("[Gadget] Composite gadget active: UAC2 + ADB")
            } else {
                logCallback("[Gadget] UAC2 gadget active")
                if (keepAdb && adbWasActive && !adbAvailable) {
                    logCallback("[Gadget] Note: ADB will reconnect when you disable the gadget.")
                }
            }
            
            return true
        } else {
            logCallback("[Gadget] Failed to bind UDC after retries.")
            return false
        }
    }
    private fun getAvailableUdcControllers(): List<String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /sys/class/udc"))
            val output = p.inputStream.bufferedReader().readText().trim()
            if (output.isEmpty()) emptyList() else output.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getPreferredUdcController(): String? {
        val controllers = getAvailableUdcControllers()
        if (controllers.isEmpty()) return null
        
        // Prefer non-dummy controllers (e.g. musb-hdrc, dwc3, etc.)
        val realController = controllers.firstOrNull { !it.contains("dummy", ignoreCase = true) }
        return realController ?: controllers.first()
    }
    
    private fun bindGadgetWithRetry(logCallback: (String) -> Unit): Boolean {
        for (i in 1..5) {
             try {
                 val udcName = getPreferredUdcController()
                 
                 if (udcName == null) {
                     logCallback("[Gadget] Error: No UDC controller found.")
                     return false
                 }
                 
                 // MTK Specific: Force device mode
                 configureMtkMode(udcName, true, logCallback)
                 
                 // Ensure UDC is writable and clear
                 Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 $GADGET_ROOT/UDC")).waitFor()
                 Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '' > $GADGET_ROOT/UDC")).waitFor()
                 Thread.sleep(200)
                 
                 logCallback("[Gadget] Binding to $udcName (Attempt $i)...")
                 Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '$udcName' > $GADGET_ROOT/UDC")).waitFor()
                 
                 Thread.sleep(300)
                 val currentUdc = getUdcContent()
                 if (currentUdc == udcName) {
                     return true
                 }
                 
                 logCallback("[Gadget] Bind attempt $i failed. UDC='$currentUdc'")
                 Thread.sleep(800)
                 
             } catch (e: Exception) {
                 logCallback("[Gadget] Exception during bind: ${e.message}")
             }
        }
        return false
    }

    suspend fun disableGadget(logCallback: (String) -> Unit, settingsRepo: SettingsRepository? = null) = withContext(Dispatchers.IO) {
        gadgetMutex.withLock {
            disableGadgetInternal(logCallback, settingsRepo)
        }
    }
    
    private suspend fun disableGadgetInternal(logCallback: (String) -> Unit, settingsRepo: SettingsRepository? = null) {
        logCallback("[Gadget] Disabling USB gadget...")
        
        // Try soft unbind first, fall back to hard
        if (!softUnbind(logCallback)) {
            hardUnbind(logCallback)
        }
        
        // Restore strings if available
        var restored = false
        if (settingsRepo != null) {
            val (origMan, origProd, origSerial) = settingsRepo.getOriginalIdentity()
            if (origMan != null && origProd != null && origSerial != null) {
                runRootCommands(listOf(
                    "echo \"$origMan\" > $GADGET_ROOT/strings/0x409/manufacturer",
                    "echo \"$origProd\" > $GADGET_ROOT/strings/0x409/product",
                    "echo \"$origSerial\" > $GADGET_ROOT/strings/0x409/serialnumber"
                )) { } 
                logCallback("[Gadget] Restored original identity.")
                settingsRepo.clearOriginalIdentity()
                restored = true
            }
        }
        
        if (!restored) {
            runRootCommands(listOf(
                "echo \"\" > $GADGET_ROOT/strings/0x409/manufacturer",
                "echo \"\" > $GADGET_ROOT/strings/0x409/product",
                "echo \"\" > $GADGET_ROOT/strings/0x409/serialnumber"
            )) {}
        }

        // Clean up our function links
        runRootCommands(listOf(
            "rm -f $GADGET_ROOT/configs/b.1/f1 || true",
            "rm -f $GADGET_ROOT/configs/b.1/f2 || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 2>/dev/null || true"
        ), logCallback)
        
        // MTK Specific: Reset device mode
        try {
            val udcName = getPreferredUdcController()
            if (udcName != null) {
                configureMtkMode(udcName, false, logCallback)
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        // Restore system USB control
        restoreUsbConfig("adb", logCallback)
        
        // Restart USB HAL if we stopped it
        startUsbHal(logCallback, settingsRepo)
        
        logCallback("[Gadget] Gadget disabled. USB restored to system control.")
    }

    suspend fun applySeLinuxPolicy(logCallback: (String) -> Unit) {
        val rules = listOf(
            "typeattribute audio_device mlstrustedobject",
            "allow untrusted_app audio_device chr_file { read write open ioctl getattr map }",
            "allow untrusted_app audio_device dir { search getattr read open }",
            "allow untrusted_app cgroup dir { search getattr read open }"
        )
        
        val tmpPath = "/data/local/tmp/uac2_policy.te"
        val policyBlob = rules.joinToString("\n")
        
        // 1. Try KernelSU via ksud sepolicy apply
        val ksuBin = "/data/adb/ksu/bin/ksud"
        if (runRootCommand("test -f $ksuBin", {})) {
            try {
                val writeCmd = "echo '$policyBlob' > $tmpPath"
                if (runRootCommand(writeCmd, {})) {
                    val applyCmd = "$ksuBin sepolicy apply $tmpPath"
                    if (runRootCommand(applyCmd, logCallback)) {
                        logCallback("[Gadget] SELinux rules applied via ksud (KernelSU)")
                        runRootCommand("rm -f $tmpPath", {})
                        return
                    }
                }
            } catch (e: Exception) {
                logCallback("[Gadget] KernelSU policy apply failed: ${e.message}")
            }
        }

        // 2. Try Magisk/APatch via magiskpolicy
        // Magisk stores binaries at /data/adb/magisk/ (MAGISKBIN)
        // APatch stores binaries at /data/adb/ap/bin/
        // Also check common alternative paths and tmpfs mount
        val magiskPolicyPaths = listOf(
            "/data/adb/magisk/magiskpolicy",  // Standard Magisk MAGISKBIN location
            "/data/adb/ap/bin/magiskpolicy",  // APatch location
            "/data/adb/magisk/supolicy",      // Symlink for SuperSU compatibility
            "/system/bin/magiskpolicy",       // System installed (rare)
            "/sbin/magiskpolicy",             // Legacy path
            "/sbin/supolicy"                  // Legacy SuperSU path
        )
        
        var magiskPolicyBin: String? = null
        for (path in magiskPolicyPaths) {
            if (runRootCommand("test -f $path", {})) {
                magiskPolicyBin = path
                logCallback("[Gadget] Found magiskpolicy at: $path")
                break
            }
        }
        
        // Also try to find magiskpolicy in Magisk's tmpfs mount
        if (magiskPolicyBin == null) {
            // Magisk creates a tmpfs mount, path can be retrieved with `magisk --path`
            val magiskTmpResult = StringBuilder()
            if (runRootCommand("magisk --path 2>/dev/null", { msg -> magiskTmpResult.append(msg) })) {
                val magiskTmp = magiskTmpResult.toString().trim()
                if (magiskTmp.isNotEmpty()) {
                    val tmpfsPolicyPath = "$magiskTmp/magiskpolicy"
                    if (runRootCommand("test -f $tmpfsPolicyPath", {})) {
                        magiskPolicyBin = tmpfsPolicyPath
                        logCallback("[Gadget] Found magiskpolicy at tmpfs: $tmpfsPolicyPath")
                    }
                }
            }
        }
        
        if (magiskPolicyBin != null) {
            try {
                // Method A: Use --apply with a rules file (preferred, more reliable)
                val writeCmd = "echo '$policyBlob' > $tmpPath"
                if (runRootCommand(writeCmd, {})) {
                    // magiskpolicy --live --apply FILE loads rules from file and applies live
                    val applyCmd = "$magiskPolicyBin --live --apply $tmpPath"
                    if (runRootCommand(applyCmd, logCallback)) {
                        logCallback("[Gadget] SELinux rules applied via magiskpolicy --apply (Magisk)")
                        runRootCommand("rm -f $tmpPath", {})
                        return
                    }
                }
                
                // Method B: Fallback to inline rules if file method fails
                logCallback("[Gadget] File-based apply failed, trying inline rules...")
                var allRulesApplied = true
                for (rule in rules) {
                    // Each rule must be quoted as a single argument
                    val ruleCmd = "$magiskPolicyBin --live '$rule'"
                    if (!runRootCommand(ruleCmd, { msg ->
                        if (msg.contains("error", ignoreCase = true) || 
                            msg.contains("denied", ignoreCase = true)) {
                            logCallback("[Gadget] Rule warning: $msg")
                        }
                    })) {
                        allRulesApplied = false
                    }
                }
                
                if (allRulesApplied) {
                    logCallback("[Gadget] SELinux rules applied via magiskpolicy --live (Magisk)")
                    runRootCommand("rm -f $tmpPath", {})
                    return
                }
            } catch (e: Exception) {
                logCallback("[Gadget] Magisk policy apply failed: ${e.message}")
            }
        }

        // 3. Fallback: Try generic supolicy command (older SuperSU)
        if (runRootCommand("which supolicy >/dev/null 2>&1", {})) {
            logCallback("[Gadget] Trying legacy supolicy...")
            var success = true
            for (rule in rules) {
                if (!runRootCommand("supolicy --live '$rule'", { msg ->
                    if (msg.contains("error", ignoreCase = true)) logCallback(msg)
                })) {
                    success = false
                }
            }
            if (success) {
                logCallback("[Gadget] SELinux rules applied via supolicy (SuperSU)")
                runRootCommand("rm -f $tmpPath", {})
                return
            }
        }

        // Cleanup temp file if it exists
        runRootCommand("rm -f $tmpPath", {})
        logCallback("[Gadget] Warning: No SELinux policy tool succeeded (tried KernelSU, Magisk, SuperSU)")
        logCallback("[Gadget] Audio device access may fail without proper SELinux rules")
    }

    suspend fun findAndPrepareCard(logCallback: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Scanning for UAC2 audio card...")
        
        var cardIndex = -1
        
        try {
            val checkCmd = "cat /proc/asound/cards | grep -i 'UAC2Gadget' | awk '{print \$1}'"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", checkCmd))
            val output = process.inputStream.bufferedReader().readText().trim()
            
            if (output.isNotEmpty()) {
                cardIndex = output.filter { it.isDigit() }.toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            logCallback("[Gadget] Error scanning cards: ${e.message}")
        }
            
        if (cardIndex != -1) {
            val devPath = "/dev/snd/pcmC${cardIndex}D0c"
            if (runRootCommand("test -e $devPath", {})) {
                 // Retry chmod in case of race conditions
                 for (i in 1..3) {
                     runRootCommand("chmod 666 /dev/snd/pcmC${cardIndex}D0c", {})
                     runRootCommand("chmod 666 /dev/snd/pcmC${cardIndex}D0p", {})
                     Thread.sleep(100)
                 }
                 logCallback("[Gadget] UAC2 driver found at card $cardIndex")
                 return@withContext cardIndex
            } else {
                logCallback("[Gadget] Card $cardIndex found, but pcmC${cardIndex}D0c is missing.")
            }
        } else {
             logCallback("[Gadget] UAC2 card not found. Is USB connected?")
        }
        
        return@withContext -1
    }

    fun runRootCommand(cmd: String, logCallback: (String) -> Unit): Boolean {
        return runRootCommands(listOf(cmd), logCallback)
    }

    fun isGadgetActive(): Boolean {
        return try {
            val cmd = "test -L $GADGET_ROOT/configs/b.1/f1 && readlink $GADGET_ROOT/configs/b.1/f1 | grep -q uac2"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    fun checkStatus(): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
        return process.inputStream.bufferedReader().readText().trim()
    }

    /**
     * Get comprehensive gadget status including UDC controller and active functions.
     * This should be called from a background thread.
     */
    fun getGadgetStatus(): GadgetStatus {
        return try {
            // Get UDC controller
            val udcProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
            val udcController = udcProcess.inputStream.bufferedReader().readText().trim()
            val isBound = udcController.isNotEmpty() && udcController != "none"

            // Get active functions by reading symlink targets in configs/b.1/
            val functionsProcess = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", 
                "for f in $GADGET_ROOT/configs/b.1/f*; do [ -L \"\$f\" ] && basename \$(readlink \"\$f\"); done 2>/dev/null"
            ))
            val functionsOutput = functionsProcess.inputStream.bufferedReader().readText().trim()
            val activeFunctions = if (functionsOutput.isNotEmpty()) {
                functionsOutput.lines()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                    .map { 
                        // Clean up function names (e.g., "uac2.0" -> "uac2", "ffs.adb" -> "adb")
                        when {
                            it.startsWith("uac2") -> "uac2"
                            it.startsWith("ffs.") -> it.removePrefix("ffs.")
                            else -> it
                        }
                    }
            } else {
                emptyList()
            }

            GadgetStatus(
                udcController = if (isBound) udcController else "--",
                activeFunctions = activeFunctions,
                isBound = isBound
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting gadget status: ${e.message}")
            GadgetStatus(
                udcController = "--",
                activeFunctions = emptyList(),
                isBound = false
            )
        }
    }

    fun runRootCommands(commands: List<String>, logCallback: (String) -> Unit): Boolean {
        var success = true
        try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)

            for (cmd in commands) {
                os.writeBytes("$cmd\n")
                Log.d(TAG, "Executing: ${cmd.take(100)}...")
            }
            os.writeBytes("exit\n")
            os.flush()

            val errReader = Thread {
                try {
                    val reader = p.errorStream.bufferedReader()
                    var errLine: String?
                    while (reader.readLine().also { errLine = it } != null) {
                        val errorLine = errLine ?: continue
                        val isBenign = errorLine.contains("No such file") ||
                                       errorLine.contains("Read-only") ||
                                       errorLine.contains("File exists") ||
                                       errorLine.contains("Directory not empty")
                        if (!isBenign) {
                            logCallback("[Shell Err] $errorLine")
                        }
                    }
                } catch (e: Exception) {
                    // Ignore read errors
                }
            }
            errReader.start()

            val exitCode = p.waitFor()
            errReader.join(1000)

            if (exitCode != 0) {
                 Log.w(TAG, "Command batch returned non-zero: $exitCode")
            }

        } catch (e: Exception) {
            logCallback("[Gadget] Command failed: ${e.message}")
            success = false
        }
        return success
    }
}