package com.flopster101.usbaudiobridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
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

    // Suspend function to run on IO thread
    suspend fun forceUnbind(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Unbinding... (Phase 1: Request 'none')")
        runRootCommands(listOf("setprop sys.usb.config none")) {}

        // Phase 1: Polite wait (3 seconds)
        for (i in 1..6) {
             Thread.sleep(500)
             if (checkUdcReleased()) {
                 logCallback("[Gadget] Unbind Clean (System handled it).")
                 return@withContext true
             }
        }

        // Phase 2: Nuclear option (Force write UDC)
        logCallback("[Gadget] Unbind Stuck. Phase 2: Forcing UDC clear...")
        runRootCommands(listOf("echo \"none\" > $GADGET_ROOT/UDC")) {}

        for (i in 1..4) { // Wait 2 more seconds
             Thread.sleep(500)
             if (checkUdcReleased()) {
                 logCallback("[Gadget] Unbind Force-Cleared (Hardware is free).")
                 return@withContext true
             }
        }

        // Final check
        val udc = getUdcContent()
        logCallback("[Gadget] Critical: Failed to unbind. UDC still holds: '$udc'")
        return@withContext false
    }

    // Helper to check if UDC is effectively free
    private fun checkUdcReleased(): Boolean {
        try {
            val udc = getUdcContent()
            // If UDC is empty or literally "none", it's free.
            // We ignore sys.usb.state here because if UDC is free, we can write to it.
            return (udc.isEmpty() || udc == "none" || udc.isBlank())
        } catch(e: Exception) { return false }
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

    suspend fun enableGadget(logCallback: (String) -> Unit, sampleRate: Int = 48000): Boolean = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Configuring UAC2 gadget ($sampleRate Hz)...")
        
        if (!forceUnbind(logCallback)) {
             logCallback("[Gadget] Aborting: Could not release USB hardware.")
             return@withContext false
        }
        
        if (!forceUnbind(logCallback)) {
             logCallback("[Gadget] Aborting: Could not release USB hardware.")
             return@withContext false
        }
        
        val bcdDevice = getBcdDeviceForRate(sampleRate)
        val pid = getPidForRate(sampleRate)
        val serial = getSerialNumberForRate(sampleRate)
        
        // Setup configfs structure first
        val configCommands = listOf(
            // Use || true to suppress benign errors if files are already gone
            "rm -f $GADGET_ROOT/configs/b.1/f1 || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 || true",
            
            // Set Device Identity (Before creating functions)
            "echo \"$VENDOR_ID\" > $GADGET_ROOT/idVendor",
            "echo \"$pid\" > $GADGET_ROOT/idProduct",
            "echo \"$bcdDevice\" > $GADGET_ROOT/bcdDevice",     // Version varies by rate
            "echo \"0x0200\" > $GADGET_ROOT/bcdUSB",        // USB 2.0
            
            "mkdir -p $GADGET_ROOT/functions/uac2.0",
            "echo $sampleRate > $GADGET_ROOT/functions/uac2.0/p_srate",
            "echo $CH_MASK > $GADGET_ROOT/functions/uac2.0/p_chmask",
            "echo $SAMPLE_SIZE > $GADGET_ROOT/functions/uac2.0/p_ssize",
            "echo $sampleRate > $GADGET_ROOT/functions/uac2.0/c_srate",
            "echo $CH_MASK > $GADGET_ROOT/functions/uac2.0/c_chmask",
            "echo $SAMPLE_SIZE > $GADGET_ROOT/functions/uac2.0/c_ssize",
            
            // Explicitly set request number (Standard: 2)
            "echo 2 > $GADGET_ROOT/functions/uac2.0/req_number || true",
            
            "mkdir -p $GADGET_ROOT/strings/0x409",
            "echo \"$MANUFACTURER\" > $GADGET_ROOT/strings/0x409/manufacturer",
            "echo \"$PRODUCT ($sampleRate Hz)\" > $GADGET_ROOT/strings/0x409/product",
            "echo \"$serial\" > $GADGET_ROOT/strings/0x409/serialnumber",
            
            // Set Configuration String (Important for Windows display in some views)
            "mkdir -p $GADGET_ROOT/configs/b.1/strings/0x409",
            "echo \"USB Audio\" > $GADGET_ROOT/configs/b.1/strings/0x409/configuration",
            

            "ln -s $GADGET_ROOT/functions/uac2.0 $GADGET_ROOT/configs/b.1/f1"
        )
        
        applySeLinuxPolicy(logCallback)
        
        // Run configuration
        if (!runRootCommands(configCommands, logCallback)) {
            logCallback("[Gadget] Failed to configure gadget structure.")
            return@withContext false
        }
        
        // Wait for system to settle before binding
        Thread.sleep(1000)
        
        // Attempt to bind with retries (Critical Step)
        if (bindGadgetWithRetry(logCallback)) {
            // Update state to match config
            runRootCommands(listOf(
                "setprop sys.usb.state uac2_managed",
                "setprop sys.usb.config uac2_managed"
            )) {}
            return@withContext true
        } else {
            logCallback("[Gadget] Failed to bind UDC after retries.")
            return@withContext false
        }
    }
    
    private fun bindGadgetWithRetry(logCallback: (String) -> Unit): Boolean {
        for (i in 1..5) {
             try {
                 // 1. Identify UDC
                 val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /sys/class/udc | head -n 1"))
                 val udcName = p.inputStream.bufferedReader().readText().trim()
                 
                 if (udcName.isEmpty()) {
                     logCallback("[Gadget] Error: No UDC controller found.")
                     return false
                 }
                 
                 // 2. Ensure it's clean (Paranoia check)
                 runRootCommands(listOf("echo \"none\" > $GADGET_ROOT/UDC || true")) {}
                 
                 // 3. Write it
                 logCallback("[Gadget] Binding to $udcName (Attempt $i)...")
                 if (runRootCommands(listOf("echo \"$udcName\" > $GADGET_ROOT/UDC"), logCallback)) {
                     return true
                 }
                 
                 logCallback("[Gadget] Bind attempt $i failed (EBUSY?). Retrying...")
                 Thread.sleep(800) // Back off
                 
             } catch (e: Exception) {
                 logCallback("[Gadget] Exception during bind: ${e.message}")
             }
        }
        return false
    }
    
    // ... applySeLinuxPolicy ...

    suspend fun disableGadget(logCallback: (String) -> Unit) = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Disabling USB gadget...")
        forceUnbind(logCallback)
        runRootCommands(listOf(
            "rm -f $GADGET_ROOT/configs/b.1/f1 || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 || true",
            "echo \"\" > $GADGET_ROOT/strings/0x409/manufacturer",
            "echo \"\" > $GADGET_ROOT/strings/0x409/product",
            // Check if we effectively disabled it, then return control to Android
            // Restore standard ADB config
            "setprop sys.usb.config adb" 
        ), logCallback)
        logCallback("[Gadget] Gadget disabled. Restored USB.")
    }

    suspend fun applySeLinuxPolicy(logCallback: (String) -> Unit) {
        val rules = listOf(
            // Allow bypassing MLS constraints (Level s0 vs s0:c2...)
            "typeattribute audio_device mlstrustedobject",
            
            // Allow rules
            "allow untrusted_app audio_device chr_file { read write open ioctl getattr map }",
            "allow untrusted_app audio_device dir { search getattr read open }",
            "allow untrusted_app cgroup dir { search getattr read open }"
        )
        
        // 1. Try KernelSU via file apply (most robust for KSU)
        val ksuBin = "/data/adb/ksu/bin/ksud"
        if (runRootCommand("test -f $ksuBin", {})) {
             try {
                 // Create a single policy string
                 val policyBlob = rules.joinToString("\n")
                 // Write to a tmp file accessible by root
                 val tmpPath = "/data/local/tmp/uac2_policy.te"
                 
                 // Echo the rules into the file
                 val writeCmd = "echo \"$policyBlob\" > $tmpPath"
                 if (runRootCommand(writeCmd, {})) {
                     val applyCmd = "$ksuBin sepolicy apply $tmpPath"
                     if (runRootCommand(applyCmd, logCallback)) {
                         logCallback("[Gadget] SELinux rules applied via ksud")
                         runRootCommand("rm $tmpPath", {}) // Cleanup
                         return
                     }
                 }
             } catch (e: Exception) {
                 logCallback("[Gadget] KSU file apply failed: ${e.message}")
             }
        }

        // 2. Fallback to MagiskPolicy / SuperSU (Direct Injection)
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
            // These tools often handle braced syntax fine, but we can unroll if needed.
            // Using the original braced rules here for conciseness as confirmed working manually on Magisk previously.
            for (rule in rules) {
                 runRootCommand("$workingTool \"$rule\"", { msg ->
                     if (msg.contains("denied") || msg.contains("Error")) logCallback(msg)
                 })
            }
            logCallback("[Gadget] SELinux rules applied.")
        } else {
             // If we are here and KSU failed, we are in trouble.
             logCallback("[Gadget] Warning: No policy tool succeeded (KSU/Magisk).")
        }
    }

    suspend fun findAndPrepareCard(logCallback: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Scanning for UAC2 audio card...")
        
        var cardIndex = -1
        
        // Find card number for UAC2Gadget
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
            val devPath = "/dev/snd/pcmC${cardIndex}D0c" // Capture device
            if (runRootCommand("test -e $devPath", {})) {
                 // Apply permissions
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
        // Simple wrapper still blocks but usually fast. For strict correctness could be suspend too but inner runRootCommands handles it.
        // We will make runRootCommands blocking but called from suspend context is fine since we are on IO dispatcher.
        return runRootCommands(listOf(cmd), logCallback)
    }



    fun isGadgetActive(): Boolean {
        // Check if our specific UAC2 function is linked in the config
        // This avoids false positives from default system gadgets (MTP/ADB)
        try {
            val cmd = "ls -l $GADGET_ROOT/configs/b.1/f1"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = p.inputStream.bufferedReader().readText().trim()
            return output.contains("uac2.0")
        } catch (e: Exception) {
            return false
        }
    }

    fun checkStatus(): String {
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/asound/cards")
            process.inputStream.bufferedReader().readText().let {
                if (it.contains("UAC2")) "Active (UAC2 Found)" else "Inactive (No UAC2)"
            }
        } catch (e: Exception) {
            "Error checking"
        }
    }

    private fun runRootCommands(cmds: List<String>, logCallback: (String) -> Unit): Boolean {
        var success = false
        try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            
            // Start threads to drain output buffers (prevents deadlocks)
            Thread {
                try {
                    val line = p.inputStream.bufferedReader().readText()
                     if (line.isNotEmpty()) {
                         // Optional: logCallback("Out: $line") 
                         // Only logging if relevant, but we capture it to prevent blocking
                     }
                } catch (_: Exception) {}
            }.start()
            
            val errReader = Thread {
                 try {
                    val reader = p.errorStream.bufferedReader()
                    var errLine = reader.readLine()
                    while (errLine != null) {
                        if (errLine.isNotEmpty()) {
                            // Filter out "Directory not empty" or "No such file or directory" for rmdir/rm commands
                            // checking if they are benign
                            val isBenign = (errLine.contains("rmdir") && (errLine.contains("No such file") || errLine.contains("Directory not empty"))) ||
                                           (errLine.contains("rm:") && errLine.contains("No such file"))
                            
                            if (!isBenign) {
                                logCallback("Root Err: $errLine")
                            }
                        }
                        errLine = reader.readLine()
                    }
                 } catch (_: Exception) {}
            }
            errReader.start()
            
            for (cmd in cmds) {
                os.writeBytes(cmd + "\n")
            }
            os.writeBytes("exit\n")
            os.flush()
            
            val exitCode = p.waitFor()
            errReader.join(1000) // Wait briefly for error processing
            
            success = (exitCode == 0)
            if (!success) logCallback("Command Failed (Code $exitCode)")
            
        } catch (e: Exception) {
            logCallback("Root Exception: ${e.message}")
        }
        return success
    }
}
