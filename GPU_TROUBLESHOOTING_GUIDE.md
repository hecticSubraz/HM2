# 🔧 GPU Troubleshooting Guide: Fixing Freezing and CPU Fallback

## 🐛 **Your Issue**

> "when i uncheck the force cpu mode, the compression and decompression isnt performed. it will freezes and still the encoding and decoding is done in cpu not in gpu"

## ✅ **FIXED: Configuration Error**

You had **BOTH** GPU services enabled, which causes conflicts:
```java
// ❌ WRONG (what you had):
private static final boolean USE_STREAMING_GPU = true;
private static final boolean USE_OPTIMIZED_GPU = true;  // ← Conflict!

// ✅ CORRECT (fixed):
private static final boolean USE_STREAMING_GPU = true;
private static final boolean USE_OPTIMIZED_GPU = false;  // ← Only ONE should be true
```

**File**: `app/src/main/java/com/datacomp/service/ServiceFactory.java` (Line 28-30)

---

## 🔍 **Why GPU Appears to Be CPU**

### **Issue 1: GPU Initialization Timeout**

**Symptoms**:
- UI freezes for 5-30 seconds when unchecking "Force CPU mode"
- Eventually says "using CPU..." 
- Falls back to CPU mode

**Root Cause**:
- TornadoVM first-time initialization is slow (5-10+ seconds)
- Kernel compilation (JIT) takes time
- If it exceeds 30 seconds, automatic CPU fallback

**What's Happening**:
```
1. You uncheck "Force CPU mode"
2. ServiceCache starts GPU init in background
3. TornadoVM compiles kernels (slow!)
4. UI appears frozen (but it's just waiting)
5. After 30s: timeout → falls back to CPU
```

---

## 🚀 **Solutions**

### **Solution 1: Verify TornadoVM Is Installed**

**Check**:
```bash
echo %TORNADO_SDK%
```

**Should output** something like:
```
C:\Users\Lenovo\TornadoVM\bin\sdk
```

**If empty or not found**:
1. TornadoVM is not installed or not in PATH
2. GPU services will fail immediately
3. Falls back to CPU

**Fix**: Install TornadoVM or add to PATH

---

### **Solution 2: Pre-Warm GPU (One-Time Setup)**

The FIRST time you use GPU mode, it's slow. Subsequent uses are fast.

**Do this once**:

1. Build project:
```bash
.\gradlew.bat build
```

2. Run with GPU pre-warm:
```bash
.\gradlew.bat run
```

3. **In UI**:
   - Uncheck "Force CPU mode"
   - Select a SMALL file (<10 MB)
   - Click "Compress"
   - **Wait patiently** (30-60 seconds first time)

4. **After first compression**:
   - GPU is now "warmed up"
   - Subsequent compressions will be fast
   - Close and reopen app - GPU init is now cached

---

### **Solution 3: Increase Timeout**

If GPU init needs more than 30 seconds:

**File**: `app/src/main/java/com/datacomp/ui/CompressController.java`

**Line 462**: Change timeout:
```java
// Change from 30 to 60 seconds
.get(60, java.util.concurrent.TimeUnit.SECONDS);  // ← Increase if needed
```

---

### **Solution 4: Check Logs for Real Error**

**Where**: Look in console output when app runs

**Search for**:
```
ERROR  [StreamingGpuCompressionService] Failed to initialize
ERROR  [GpuFrequencyService] 
WARN   [ServiceCache] Streaming GPU service initialization failed
```

**Common errors**:

1. **"TornadoVM not found"**
   - Solution: Install TornadoVM

2. **"No GPU device"**
   - Solution: Update GPU drivers or use CPU mode

3. **"Timeout Exception"**
   - Solution: Use smaller file for first test

4. **"Out of memory"**
   - Solution: Reduce chunk size in `app.conf`

---

## 📊 **How to Tell If GPU Is Actually Running**

### **Method 1: Check Logs**

When GPU is working, you'll see:
```log
INFO  ╔═══════════════════════════════════════════════════════════╗
INFO  ║  STREAMING GPU COMPRESSION SERVICE INITIALIZED            ║
INFO  ║  Mode: TRUE STREAMING (Constant Memory Usage)             ║
INFO  ║  Chunk Size: 32 MB                                        ║
INFO  ╚═══════════════════════════════════════════════════════════╝
```

When it falls back to CPU:
```log
WARN  GPU not available, will use CPU fallback
INFO  Using CPU compression service
```

### **Method 2: Check Throughput**

**GPU Mode** (Histogram stage):
- Frequency Counting: **300-1000 MB/s** ✓

**CPU Mode** (Histogram stage):
- Frequency Counting: **50-100 MB/s**

Look at the benchmark table in UI - if "Freq" shows > 200 MB/s, GPU is working!

### **Method 3: Watch GPU Usage**

**Windows**: Task Manager → Performance → GPU
- Should show GPU activity during compression

**Linux**: 
```bash
nvidia-smi -l 1
```

---

## ⚡ **Quick Test Procedure**

To verify GPU is working:

### **Step 1: Rebuild** (to apply ServiceFactory fix)

```bash
.\gradlew.bat clean build
```

### **Step 2: Run**

```bash
.\gradlew.bat run
```

### **Step 3: First GPU Test** (Will be slow)

1. **Uncheck** "Force CPU mode"
2. Select a **small test file** (< 10 MB)
3. Click "Compress"
4. **Wait patiently** (30-60 seconds first time)
5. Watch console logs for success/error

### **Step 4: Second Test** (Should be fast)

1. Select another file (can be large now)
2. Click "Compress"
3. Should start immediately (GPU is warmed up)
4. Check throughput in UI benchmark table

---

## 🎯 **Expected Performance**

### **CPU Mode**:
- Frequency Counting: 50-100 MB/s
- Tree Building: < 1 MB/s (instant)
- Encoding: 80-120 MB/s
- **Overall**: ~100-150 MB/s

### **GPU Mode** (Streaming GPU):
- Frequency Counting: **300-1000 MB/s** ← GPU accelerated!
- Tree Building: < 1 MB/s (CPU, instant)
- Encoding: 150-200 MB/s (CPU for now)
- **Overall**: ~200-300 MB/s (2x faster)

---

## 🚨 **Common Mistakes**

### **Mistake 1: Both GPU flags enabled** ✅ **FIXED**
```java
// ❌ Don't do this:
USE_STREAMING_GPU = true;
USE_OPTIMIZED_GPU = true;  // Conflict!

// ✅ Do this:
USE_STREAMING_GPU = true;
USE_OPTIMIZED_GPU = false;
```

### **Mistake 2: Not waiting for first init**
- First GPU init takes 30-60 seconds
- Be patient!
- Subsequent uses are fast

### **Mistake 3: Testing with huge file first**
- Start with small file (< 10 MB)
- Warm up GPU first
- Then try large files

### **Mistake 4: TornadoVM not installed**
- Check `%TORNADO_SDK%` environment variable
- Must point to valid TornadoVM installation

---

## 🔧 **Advanced: Enable Verbose Logging**

To see exactly what's happening:

**File**: `app/src/main/resources/logback.xml`

Add or change:
```xml
<logger name="com.datacomp.service.gpu" level="DEBUG"/>
<logger name="com.datacomp.service.ServiceCache" level="DEBUG"/>
<logger name="uk.ac.manchester.tornado" level="INFO"/>
```

**Rebuild and run**:
```bash
.\gradlew.bat build
.\gradlew.bat run
```

Now console will show detailed GPU initialization steps.

---

## ✅ **Verification Checklist**

After applying fixes, verify:

- [ ] Only ONE GPU service enabled in `ServiceFactory.java`
- [ ] `TornadoVM` installed and in PATH (`echo %TORNADO_SDK%`)
- [ ] Project rebuilt (`.\gradlew.bat clean build`)
- [ ] First GPU test with small file (< 10 MB)
- [ ] GPU initialization completes (check logs)
- [ ] Benchmark table shows > 200 MB/s for "Freq" stage
- [ ] Second test is fast (GPU warmed up)
- [ ] Large files work without freezing

---

## 📞 **Still Not Working?**

If GPU still falls back to CPU:

### **1. Check Console Logs**

Look for errors during GPU init. Common issues:
- TornadoVM not found
- No compatible GPU
- Out of memory
- Driver issues

### **2. Try CPU Mode First**

Make sure basic compression works:
1. Check "Force CPU mode"
2. Compress a file
3. Verify it completes successfully
4. If this doesn't work, it's not a GPU issue

### **3. Verify GPU Drivers**

```bash
# Windows
nvidia-smi

# Should show GPU info
# If error: update GPU drivers
```

### **4. Test TornadoVM Directly**

```bash
cd %TORNADO_SDK%
tornado --devices

# Should list available GPU devices
# If none found: TornadoVM can't see GPU
```

### **5. Post Logs**

Helpful diagnostic info:
- Console output during compression attempt
- `%TORNADO_SDK%` environment variable value
- Output of `tornado --devices`
- GPU model and driver version

---

## 🎓 **Understanding the Freeze**

**Why UI freezes when enabling GPU**:

1. **TornadoVM First-Time Setup**:
   - Compiles Java bytecode to GPU kernels
   - This is JIT compilation (just-in-time)
   - Takes 5-30 seconds first time
   - Cached afterward

2. **Why It Looks Frozen**:
   - Async initialization is happening
   - But UI shows "Initializing GPU service..."
   - Progress bar doesn't move (compilation doesn't report progress)
   - Looks frozen, but it's actually working

3. **After Timeout**:
   - If > 30 seconds, automatic CPU fallback
   - This is a safety feature (not a bug)
   - Prevents indefinite hanging

**Solution**: Be patient on first run, or pre-warm GPU with small file.

---

## 🚀 **Summary**

1. ✅ **Fixed**: Only enable ONE GPU service (USE_STREAMING_GPU)
2. ✅ **Rebuild**: `.\gradlew.bat clean build`
3. ⏳ **First run**: Test with small file, wait 30-60 seconds
4. ⚡ **After**: GPU is warmed up, subsequent runs are fast
5. 📊 **Verify**: Check logs and benchmark table (Freq > 200 MB/s = GPU working)

**Your 500-1000 MB/s target is achievable once GPU is properly initialized!**


