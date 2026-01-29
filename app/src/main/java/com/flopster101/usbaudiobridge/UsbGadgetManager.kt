package com.flopster101.usbaudiobridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream

object UsbGadgetManager {
    private const val TAG = "UsbGadgetManager"
    
    private const val GADGET_ROOT = "/config/usb_gadget/g1"
    private const val SAMPLE_RATE = 48000
    private const val CH_MASK = 3
    private const val SAMPLE_SIZE = 2
    
    private const val MANUFACTURER = "FloppyKernel Project"
    private const val PRODUCT = "Android USB Audio Monitor"

    // Suspend function to run on IO thread
    suspend fun forceUnbind(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // 1. Take ownership from Android System to stop 'init' from fighting us
        // 'uac2_managed' is unknown to init.rc, so it should stop interfering.
        runRootCommands(listOf("setprop sys.usb.config uac2_managed")) {}
        
        for (i in 1..5) {
            runRootCommands(listOf("echo none > $GADGET_ROOT/UDC")) {}
            try {
                Thread.sleep(1000)
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
                val output = p.inputStream.bufferedReader().readText().trim()
                if (output.isEmpty() || output == "none") {
                    return@withContext true 
                }
                logCallback("[Gadget] Unbind retry $i: UDC still holds '$output'")
            } catch (e: Exception) {
               // Ignore
            }
        }
        logCallback("[Gadget] Warning: Failed to unbind UDC. Init might still be fighting.")
        return@withContext false
    }

    suspend fun enableGadget(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Configuring UAC2 gadget...")
        
        if (!forceUnbind(logCallback)) {
             logCallback("[Gadget] Continuing despite unbind failure (Risk of Device Busy)...")
        }
        
        val commands = listOf(
            // Use || true to suppress benign errors if files are already gone
            "rm -f $GADGET_ROOT/configs/b.1/f1 || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 || true",
            "mkdir -p $GADGET_ROOT/functions/uac2.0",
            "echo $SAMPLE_RATE > $GADGET_ROOT/functions/uac2.0/p_srate",
            "echo $CH_MASK > $GADGET_ROOT/functions/uac2.0/p_chmask",
            "echo $SAMPLE_SIZE > $GADGET_ROOT/functions/uac2.0/p_ssize",
            "echo $SAMPLE_RATE > $GADGET_ROOT/functions/uac2.0/c_srate",
            "echo $CH_MASK > $GADGET_ROOT/functions/uac2.0/c_chmask",
            "echo $SAMPLE_SIZE > $GADGET_ROOT/functions/uac2.0/c_ssize",
            
            "mkdir -p $GADGET_ROOT/strings/0x409",
            "echo \"$MANUFACTURER\" > $GADGET_ROOT/strings/0x409/manufacturer",
            "echo \"$PRODUCT\" > $GADGET_ROOT/strings/0x409/product",
            "ln -s $GADGET_ROOT/functions/uac2.0 $GADGET_ROOT/configs/b.1/f1",
            // Single source UDC
            "ls /sys/class/udc | head -n 1 > $GADGET_ROOT/UDC"
        )
        
        applySeLinuxPolicy(logCallback)
        
        val success = runRootCommands(commands, logCallback)
        if (success) {
            // Update state to match config
            runRootCommands(listOf("setprop sys.usb.state uac2_managed")) {}
        }
        return@withContext success
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

    private fun runRootCommand(cmd: String, logCallback: (String) -> Unit): Boolean {
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
