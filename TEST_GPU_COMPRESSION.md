# Testing GPU-Accelerated Compression

This document provides comprehensive testing instructions for the GPU-accelerated parallel compression implementation.

## Prerequisites

1. **TornadoVM Installed**:
   ```bash
   # Verify TornadoVM installation
   ./gradlew debugTornadoDeps
   ```

2. **GPU Available**:
   - NVIDIA GPU with CUDA support, or
   - AMD GPU with OpenCL support, or
   - Intel GPU with OpenCL support

3. **Java 21**:
   ```bash
   java --version
   ```

## Quick Start Test

### 1. Generate Test File

```bash
# Generate a 1GB test file
./gradlew generateLargeTestFile -Psize=1024
```

This creates `test_data_1024MB.bin` in the project root.

### 2. Compress with GPU

```bash
# Compress using GPU (64MB chunks)
./gradlew compress -Pinput=test_data_1024MB.bin -Poutput=test_data_1024MB.dc -Pchunk=64
```

### 3. Decompress and Verify

```bash
# Decompress
./gradlew decompress -Pinput=test_data_1024MB.dc -Poutput=test_data_1024MB_restored.bin

# Verify integrity (Linux/Mac)
sha256sum test_data_1024MB.bin test_data_1024MB_restored.bin

# Verify integrity (Windows PowerShell)
certutil -hashfile test_data_1024MB.bin SHA256
certutil -hashfile test_data_1024MB_restored.bin SHA256

# Bit-by-bit comparison (Linux/Mac)
diff test_data_1024MB.bin test_data_1024MB_restored.bin && echo "Files are identical!"

# Bit-by-bit comparison (Windows PowerShell)
if ((Get-FileHash test_data_1024MB.bin).Hash -eq (Get-FileHash test_data_1024MB_restored.bin).Hash) { 
    Write-Host "Files are identical!" 
}
```

## Comprehensive Test Suite

### Test Case 1: Small File (100MB)

**Purpose**: Verify basic functionality and low-chunk overhead.

```bash
# Generate
./gradlew generateLargeTestFile -Psize=100

# Compress
./gradlew compress -Pinput=test_data_100MB.bin -Poutput=test_100MB.dc -Pchunk=64

# Decompress
./gradlew decompress -Pinput=test_100MB.dc -Poutput=test_100MB_restored.bin

# Verify
sha256sum test_data_100MB.bin test_100MB_restored.bin
```

**Expected Results**:
- Compression ratio: ~50-90% (depending on data)
- Throughput: 100-300 MB/s (GPU mode)
- Chunks: 2 chunks (64MB each)
- Verification: Checksums match

### Test Case 2: Medium File (5GB)

**Purpose**: Test multi-chunk processing and GPU batch efficiency.

```bash
# Generate
./gradlew generateLargeTestFile -Psize=5120

# Compress
./gradlew compress -Pinput=test_data_5120MB.bin -Poutput=test_5GB.dc -Pchunk=64

# Decompress
./gradlew decompress -Pinput=test_5GB.dc -Poutput=test_5GB_restored.bin

# Verify
sha256sum test_data_5120MB.bin test_5GB_restored.bin
```

**Expected Results**:
- Chunks: 80 chunks (64MB each)
- Throughput: 200-500 MB/s (GPU mode)
- Time: ~10-25 seconds (GPU) vs ~50-100 seconds (CPU)
- Verification: Checksums match
- Memory usage: < 500MB

### Test Case 3: Large File (10GB)

**Purpose**: Test large file handling and memory management.

```bash
# Generate
./gradlew generateLargeTestFile -Psize=10240

# Compress
./gradlew compress -Pinput=test_data_10240MB.bin -Poutput=test_10GB.dc -Pchunk=64

# Decompress
./gradlew decompress -Pinput=test_10GB.dc -Poutput=test_10GB_restored.bin

# Verify
sha256sum test_data_10240MB.bin test_10GB_restored.bin
```

**Expected Results**:
- Chunks: 160 chunks (64MB each)
- Throughput: 200-500 MB/s (GPU mode)
- Time: ~20-50 seconds (GPU) vs ~100-200 seconds (CPU)
- Verification: Checksums match
- Memory usage: < 500MB (constant)

### Test Case 4: Very Large File (30GB)

**Purpose**: Stress test maximum file size support.

```bash
# Generate (WARNING: This will take several minutes and use 30GB disk space)
./gradlew generateLargeTestFile -Psize=30720

# Compress
./gradlew compress -Pinput=test_data_30720MB.bin -Poutput=test_30GB.dc -Pchunk=64

# Decompress
./gradlew decompress -Pinput=test_30GB.dc -Poutput=test_30GB_restored.bin

# Verify
sha256sum test_data_30720MB.bin test_30GB_restored.bin
```

**Expected Results**:
- Chunks: 480 chunks (64MB each)
- Throughput: 200-500 MB/s (GPU mode)
- Time: ~60-150 seconds (GPU) vs ~300-600 seconds (CPU)
- Verification: Checksums match
- Memory usage: < 1GB (constant, with periodic GC)

## GUI Testing

### Basic GUI Test

1. **Launch Application**:
   ```bash
   ./gradlew runTornado
   ```

2. **Navigate to Compress/Decompress View**

3. **Select Test File**:
   - Click "Browse..." next to Input File
   - Select `test_data_1024MB.bin`

4. **Compress File**:
   - Leave "Force CPU mode" unchecked (GPU mode)
   - Click "Compress"

5. **Observe**:
   - **Progress Bar**: Should smoothly increase from 0% to 100%
   - **Benchmark Table**: Should populate with colored entries:
     - Blue entries: Frequency Counting
     - Green entries: Tree Building
     - Amber entries: Encoding
   - **Summary Statistics**: Should show totals at bottom:
     - Freq: X.XXs (XX.X MB/s) - Blue
     - Tree: X.XXs (XX.X MB/s) - Green
     - Encode: X.XXs (XX.X MB/s) - Amber
   - **Throughput**: Should update in real-time
   - **ETA**: Should count down to 0
   - **Status**: Should show "Compression complete!" in green

6. **Decompress File**:
   - Select the compressed file (e.g., `test_data_1024MB.bin.dc`)
   - Click "Decompress"

7. **Observe**:
   - **Progress Bar**: Updates smoothly
   - **Benchmark Table**: Shows:
     - Purple entries: Decoding
     - Red entries: Checksum Validation
   - **Summary Statistics**: Shows totals for Decode and Checksum
   - **Status**: Shows "Decompression complete!" in green

### GUI Stress Test

Test with 10GB file:

1. Generate 10GB test file (if not already generated)
2. Launch GUI: `./gradlew runTornado`
3. Select 10GB file
4. Click "Compress"
5. Verify:
   - UI remains responsive throughout
   - Benchmark table scrolls automatically
   - Progress bar updates smoothly
   - No UI freezes or hangs
   - Status updates correctly
6. Monitor logs: `tail -f app/logs/datacomp.log`

## Performance Benchmarking

### GPU vs CPU Comparison

Test the same file with both GPU and CPU modes:

```bash
# GPU Mode (default)
time ./gradlew compress -Pinput=test_data_5120MB.bin -Poutput=test_5GB_gpu.dc -Pchunk=64

# CPU Mode (force CPU)
time ./gradlew compress -Pinput=test_data_5120MB.bin -Poutput=test_5GB_cpu.dc -Pchunk=64
```

Compare:
- Total time
- Throughput (MB/s)
- Compression ratio (should be identical)
- File sizes (should be identical or very close)

**Expected Speedup**: 3-5x faster with GPU

### Chunk Size Impact

Test different chunk sizes:

```bash
# 32MB chunks
./gradlew compress -Pinput=test_data_1024MB.bin -Poutput=test_32MB.dc -Pchunk=32

# 64MB chunks (default, optimal)
./gradlew compress -Pinput=test_data_1024MB.bin -Poutput=test_64MB.dc -Pchunk=64

# 128MB chunks
./gradlew compress -Pinput=test_data_1024MB.bin -Poutput=test_128MB.dc -Pchunk=128
```

Compare:
- Number of chunks
- Compression time
- Throughput
- GPU utilization

**Expected Results**:
- 32MB: More chunks, better parallelism, slightly higher overhead
- 64MB: Optimal balance
- 128MB: Fewer chunks, less parallelism, slightly lower overhead

## Integrity Testing

### Checksum Verification

Every decompression automatically validates checksums. Test explicit verification:

```bash
# This should succeed
./gradlew decompress -Pinput=test_data_1024MB.dc -Poutput=test_restored.bin

# Check logs for checksum validation
grep "Checksum" app/logs/datacomp.log
```

**Expected Output**:
```
Checksum validation passed for all X chunks
File integrity verified - decompressed XXXXX bytes successfully
```

### Corruption Detection

Simulate corruption and verify detection:

```bash
# Compress file
./gradlew compress -Pinput=test_data_100MB.bin -Poutput=test_corrupted.dc -Pchunk=64

# Corrupt the compressed file (Linux/Mac)
dd if=/dev/urandom of=test_corrupted.dc bs=1 count=100 seek=5000 conv=notrunc

# Corrupt the compressed file (Windows PowerShell)
$bytes = New-Object byte[] 100
(New-Object Random).NextBytes($bytes)
$stream = [System.IO.File]::OpenWrite("test_corrupted.dc")
$stream.Seek(5000, [System.IO.SeekOrigin]::Begin)
$stream.Write($bytes, 0, 100)
$stream.Close()

# Attempt to decompress (should fail with checksum error)
./gradlew decompress -Pinput=test_corrupted.dc -Poutput=test_should_fail.bin
```

**Expected Result**: Decompression fails with error message:
```
Checksum mismatch in chunk X - data integrity compromised!
```

## Memory Profiling

Monitor memory usage during large file compression:

### Linux/Mac

```bash
# In one terminal, start compression
./gradlew compress -Pinput=test_data_10240MB.bin -Poutput=test_10GB.dc -Pchunk=64

# In another terminal, monitor memory
watch -n 1 'ps aux | grep DataCompApp'
```

### Windows PowerShell

```powershell
# Start compression in background
Start-Process gradlew -ArgumentList "compress", "-Pinput=test_data_10240MB.bin", "-Poutput=test_10GB.dc", "-Pchunk=64"

# Monitor memory
while ($true) {
    Get-Process | Where-Object {$_.ProcessName -like "*java*"} | Format-Table ProcessName, WS -AutoSize
    Start-Sleep -Seconds 1
}
```

**Expected Memory Usage**:
- Initial: ~500MB
- During compression: ~500MB-1GB
- Peak: < 2GB (even for 30GB files)
- Steady state: Constant (no memory leaks)

## Error Handling Tests

### Test 1: Invalid Input File

```bash
./gradlew compress -Pinput=nonexistent.bin -Poutput=output.dc -Pchunk=64
```

**Expected**: Error message about file not found

### Test 2: Insufficient Disk Space

```bash
# Create large file on nearly full disk
# (Requires manual setup - use small partition or quota)
```

**Expected**: Error message about disk space

### Test 3: GPU Unavailable

```bash
# Disable GPU or set to force CPU mode
./gradlew compress -Pinput=test_data_100MB.bin -Poutput=test.dc -Pchunk=64
# (With useCpu checkbox enabled in GUI)
```

**Expected**: Falls back to CPU mode gracefully

## Test Results Template

Use this template to record test results:

```
## Test Results

### Environment
- OS: ___________
- GPU: ___________
- TornadoVM Version: ___________
- Java Version: ___________

### Test Case 1: Small File (100MB)
- Compression Time: _____ seconds
- Decompression Time: _____ seconds
- Throughput: _____ MB/s
- Compression Ratio: _____%
- Checksums Match: YES / NO

### Test Case 2: Medium File (5GB)
- Compression Time: _____ seconds
- Decompression Time: _____ seconds
- Throughput: _____ MB/s
- Compression Ratio: _____%
- Checksums Match: YES / NO

### Test Case 3: Large File (10GB)
- Compression Time: _____ seconds
- Decompression Time: _____ seconds
- Throughput: _____ MB/s
- Compression Ratio: _____%
- Checksums Match: YES / NO

### Test Case 4: Very Large File (30GB)
- Compression Time: _____ seconds
- Decompression Time: _____ seconds
- Throughput: _____ MB/s
- Compression Ratio: _____%
- Checksums Match: YES / NO

### GPU vs CPU Speedup
- File Size: _____ GB
- GPU Time: _____ seconds
- CPU Time: _____ seconds
- Speedup: _____x

### Memory Usage
- Peak Memory (10GB file): _____ MB
- Steady State Memory: _____ MB
- Memory Leaks: YES / NO

### UI Testing
- Progress Bar Smooth: YES / NO
- Benchmark Table Displays: YES / NO
- Color-Coded Stages: YES / NO
- Summary Statistics Accurate: YES / NO
- UI Responsive: YES / NO

### Error Handling
- Corruption Detection: PASS / FAIL
- Invalid Input Handling: PASS / FAIL
- GPU Fallback: PASS / FAIL

### Overall Assessment
- All Tests Passed: YES / NO
- Ready for Production: YES / NO
- Issues Found: ___________
```

## Troubleshooting

### GPU Not Detected

```bash
# Check TornadoVM configuration
./gradlew debugTornadoDeps

# Check GPU devices
# (TornadoVM should list available devices on startup)
```

### Out of Memory Errors

```bash
# Increase heap size in build.gradle
-Xmx8g  # or higher
```

### Slow Performance

1. **Verify GPU is being used**: Check logs for "GPU Compression" messages
2. **Check chunk size**: 64MB is optimal
3. **Monitor GPU utilization**: Use `nvidia-smi` (NVIDIA) or equivalent
4. **Check PCIe bandwidth**: Ensure GPU is in correct PCIe slot

### Checksum Failures

1. **File corruption**: Verify input file integrity
2. **Disk errors**: Run disk check utilities
3. **Bug in implementation**: Report issue with logs

## Continuous Integration

For CI/CD pipelines, use automated tests:

```bash
# Run all unit tests
./gradlew test

# Run GPU-specific tests
./gradlew testGpu

# Automated integration test script
./test_integration.sh
```

## Conclusion

After completing all tests, you should have:
- ✓ Verified compression and decompression work correctly
- ✓ Confirmed bit-accurate results with checksum validation
- ✓ Tested files up to 30GB
- ✓ Measured GPU vs CPU performance
- ✓ Validated UI displays benchmark metrics correctly
- ✓ Confirmed memory usage is constant and reasonable
- ✓ Tested error handling and corruption detection



