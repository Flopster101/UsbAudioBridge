package com.flopster101.usbaudiobridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

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

    private fun getSerialNumberForRate(rate: Int): String {
        return "UAM-SR$rate"
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
        
        // Backup original strings if not already done
        if (settingsRepo != null) {
            val (savedMan, savedProd) = settingsRepo.getOriginalIdentity()
            if (savedMan == null || savedProd == null) {
                try {
                    val pProd = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/product"))
                    val currProd = pProd.inputStream.bufferedReader().readText().trim()
                    
                    val pMan = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/manufacturer"))
                    val currMan = pMan.inputStream.bufferedReader().readText().trim()
                    
                    if (currProd.isNotEmpty() && !currProd.contains("Audio Bridge") && !currMan.contains("FloppyKernel")) {
                        settingsRepo.saveOriginalIdentity(currMan, currProd)
                        logCallback("[Gadget] Backed up original identity: $currMan - $currProd")
                    } else {
                        val pModel = Runtime.getRuntime().exec("getprop ro.product.model")
                        val fallbackProd = pModel.inputStream.bufferedReader().readText().trim()
                        val pBrand = Runtime.getRuntime().exec("getprop ro.product.manufacturer")
                        val fallbackMan = pBrand.inputStream.bufferedReader().readText().trim()
                        settingsRepo.saveOriginalIdentity(fallbackMan, fallbackProd)
                        logCallback("[Gadget] Backed up identity from props: $fallbackMan - $fallbackProd")
                    }
                } catch (e: Exception) {
                    logCallback("[Gadget] Failed to backup strings: ${e.message}")
                }
            }
        }
        
        val bcdDevice = getBcdDeviceForRate(sampleRate)
        val pid = getPidForRate(sampleRate)
        val serial = getSerialNumberForRate(sampleRate)
        
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
    
    private fun bindGadgetWithRetry(logCallback: (String) -> Unit): Boolean {
        for (i in 1..5) {
             try {
                 val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /sys/class/udc | head -n 1"))
                 val udcName = p.inputStream.bufferedReader().readText().trim()
                 
                 if (udcName.isEmpty()) {
                     logCallback("[Gadget] Error: No UDC controller found.")
                     return false
                 }
                 
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
            val (origMan, origProd) = settingsRepo.getOriginalIdentity()
            if (origMan != null && origProd != null) {
                runRootCommands(listOf(
                    "echo \"$origMan\" > $GADGET_ROOT/strings/0x409/manufacturer",
                    "echo \"$origProd\" > $GADGET_ROOT/strings/0x409/product"
                )) { } 
                logCallback("[Gadget] Restored original identity.")
                settingsRepo.clearOriginalIdentity()
                restored = true
            }
        }
        
        if (!restored) {
            runRootCommands(listOf(
                "echo \"\" > $GADGET_ROOT/strings/0x409/manufacturer",
                "echo \"\" > $GADGET_ROOT/strings/0x409/product"
            )) {}
        }

        // Clean up our function links
        runRootCommands(listOf(
            "rm -f $GADGET_ROOT/configs/b.1/f1 || true",
            "rm -f $GADGET_ROOT/configs/b.1/f2 || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 2>/dev/null || true"
        ), logCallback)
        
        // Restore system USB control
        restoreUsbConfig("adb", logCallback)
        logCallback("[Gadget] Gadget disabled. USB restored to system control.")
    }

    suspend fun applySeLinuxPolicy(logCallback: (String) -> Unit) {
        val rules = listOf(
            "typeattribute audio_device mlstrustedobject",
            "allow untrusted_app audio_device chr_file { read write open ioctl getattr map }",
            "allow untrusted_app audio_device dir { search getattr read open }",
            "allow untrusted_app cgroup dir { search getattr read open }"
        )
        
        // 1. Try KernelSU via file apply
        val ksuBin = "/data/adb/ksu/bin/ksud"
        if (runRootCommand("test -f $ksuBin", {})) {
             try {
                 val policyBlob = rules.joinToString("\n")
                 val tmpPath = "/data/local/tmp/uac2_policy.te"
                 
                 val writeCmd = "echo \"$policyBlob\" > $tmpPath"
                 if (runRootCommand(writeCmd, {})) {
                     val applyCmd = "$ksuBin sepolicy apply $tmpPath"
                     if (runRootCommand(applyCmd, logCallback)) {
                         logCallback("[Gadget] SELinux rules applied via ksud")
                         runRootCommand("rm $tmpPath", {})
                         return
                     }
                 }
             } catch (e: Exception) {
                 logCallback("[Gadget] KSU file apply failed: ${e.message}")
             }
        }

        // 2. Fallback to MagiskPolicy / SuperSU
        val tools = listOf(
            "magiskpolicy --live",
            "supolicy --live"
        )
        
        var workingTool: String? = null
        for (tool in tools) {
             if (runRootCommand("$tool \"allow untrusted_app audio_device chr_file getattr\"", {})) {
                 workingTool = tool
                 break
             }
        }
        
        if (workingTool != null) {
            logCallback("[Gadget] Applying policy via ${workingTool}...")
            for (rule in rules) {
                 runRootCommand("$workingTool \"$rule\"", { msg ->
                     if (msg.contains("denied") || msg.contains("Error")) logCallback(msg)
                 })
            }
            logCallback("[Gadget] SELinux rules applied.")
        } else {
             logCallback("[Gadget] Warning: No policy tool succeeded (KSU/Magisk).")
        }
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
                 runRootCommand("chmod 666 /dev/snd/pcmC${cardIndex}D0c", logCallback)
                 runRootCommand("chmod 666 /dev/snd/pcmC${cardIndex}D0p", {})
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
        val cmd = "test -L $GADGET_ROOT/configs/b.1/f1 && readlink $GADGET_ROOT/configs/b.1/f1 | grep -q uac2"
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        return try {
             p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    fun checkStatus(): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
        return process.inputStream.bufferedReader().readText().trim()
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