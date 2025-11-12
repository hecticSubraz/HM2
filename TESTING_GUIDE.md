# Testing Guide for Compression/Decompression Fixes

## Quick Test Commands

### Using the GUI

```bash
# Start the GUI application
./gradlew run

# Or with TornadoVM GPU acceleration
./gradlew runTornado
```

**In the GUI**:
1. Select "Compress" or "Decompress" tab
2. Click "Select File" to choose your input file
3. Click "Compress" or "Decompress" button
4. Watch the progress bar and benchmark metrics
5. Verify the output file is created successfully

### Using Command Line

#### Compression
```bash
# Basic compression (CPU)
./gradlew compress -Pinput=myfile.pdf -Poutput=myfile.dc

# GPU compression
./gradlew compress -Pinput=myfile.pdf -Poutput=myfile.dc -Pmode=gpu

# With custom chunk size (in MB)
./gradlew compress -Pinput=myfile.pdf -Poutput=myfile.dc -Pchunk=128
```

#### Decompression
```bash
# Basic decompression
./gradlew decompress -Pinput=myfile.dc -Poutput=restored.pdf

# Verify the files match
diff myfile.pdf restored.pdf
# OR on Windows PowerShell:
fc myfile.pdf restored.pdf
```

## Test Cases

### Test 1: Small PDF File
**Purpose**: Verify PDF compression works correctly

```bash
# Compress
./gradlew compress -Pinput=test.pdf -Poutput=test.dc

# Check compressed size (should be 70-90% of original for PDFs)
# On Windows PowerShell:
(Get-Item test.pdf).Length
(Get-Item test.dc).Length

# Decompress
./gradlew decompress -Pinput=test.dc -Poutput=test_restored.pdf

# Verify integrity
fc test.pdf test_restored.pdf
```

**Expected Result**:
- ✅ Compression completes without errors
- ✅ Compressed file size is 70-90% of original (PDFs are pre-compressed)
- ✅ Decompression completes without errors
- ✅ Files are identical (fc reports no differences)

### Test 2: Large Binary File
**Purpose**: Test handling of large files (stress test)

```bash
# Create a 100MB test file with random data
# On Windows PowerShell:
$bytes = New-Object byte[] (100MB)
[System.Random]::new().NextBytes($bytes)
[System.IO.File]::WriteAllBytes("test_large.bin", $bytes)

# Compress
./gradlew compress -Pinput=test_large.bin -Poutput=test_large.dc -Pchunk=64

# Decompress
./gradlew decompress -Pinput=test_large.dc -Poutput=test_large_restored.bin

# Verify
fc test_large.bin test_large_restored.bin
```

**Expected Result**:
- ✅ Compression handles large file without out-of-memory errors
- ✅ Progress bar updates smoothly
- ✅ Benchmark metrics show reasonable throughput
- ✅ Decompression produces identical file

### Test 3: Edge Case - Single Symbol File
**Purpose**: Test the fixed zero-length code bug

```bash
# Create a file with all zeros (single symbol)
# On Windows PowerShell:
$zeros = New-Object byte[] (10MB)
[System.IO.File]::WriteAllBytes("test_zeros.bin", $zeros)

# Compress
./gradlew compress -Pinput=test_zeros.bin -Poutput=test_zeros.dc

# Check compression ratio (should be excellent for repetitive data)
(Get-Item test_zeros.bin).Length
(Get-Item test_zeros.dc).Length

# Decompress
./gradlew decompress -Pinput=test_zeros.dc -Poutput=test_zeros_restored.bin

# Verify
fc test_zeros.bin test_zeros_restored.bin
```

**Expected Result**:
- ✅ Compression works (no more "decode error at position 0")
- ✅ Excellent compression ratio (< 1% of original)
- ✅ Decompression works correctly
- ✅ Files are identical

### Test 4: Text File
**Purpose**: Verify text compression works well

```bash
# Use any text file (README, source code, etc.)
./gradlew compress -Pinput=README.md -Poutput=README.dc

# Decompress
./gradlew decompress -Pinput=README.dc -Poutput=README_restored.md

# Verify content
fc README.md README_restored.md

# Check compression ratio (should be 40-60% for text)
(Get-Item README.md).Length
(Get-Item README.dc).Length
```

**Expected Result**:
- ✅ Good compression ratio (40-60% of original for text)
- ✅ Decompression produces identical file
- ✅ Text content is preserved perfectly

## Interpreting Results

### Compression Ratios (Realistic Values)

| File Type | Expected Compression Ratio | Notes |
|-----------|---------------------------|-------|
| Plain Text | 40-60% | Good compression |
| Source Code | 45-65% | Good compression |
| PDF | 70-95% | Already compressed |
| JPEG/PNG | 95-102% | Already compressed |
| ZIP/RAR | 98-105% | Already compressed |
| Random Data | 99-101% | Incompressible |
| Repetitive Data (zeros) | 0.1-5% | Excellent compression |

### Warning Signs

❌ **BAD** (Indicates Bug):
```
Compressing 500KB → 50 bytes (0.01%)  ← Too good to be true!
Decompression failed at position 0     ← Immediate failure
```

✅ **GOOD** (Expected):
```
Compressing 500KB PDF → 425KB (85%)   ← Realistic
Decompression successful               ← Works!
Files are identical                    ← Bit-accurate
```

## Benchmark Metrics

The GUI and logs will show:
- **Frequency Counting Time**: < 100ms for most files
- **Tree Building Time**: < 10ms (very fast)
- **Encoding Time**: Varies by file size (50-200 MB/s)
- **Decoding Time**: Varies by file size (40-150 MB/s)
- **Throughput**: Overall MB/s for compression/decompression

**Example Good Output**:
```
GPU Compressing test.pdf (512 KB) into 1 chunks
Chunk 1/1 compressed: 524288 -> 445833 bytes (85.04%)
Compression complete: 524288 -> 445833 bytes in 0.52s (964 MB/s)
```

## Troubleshooting

### Error: "No Huffman code for symbol X at position Y"
**Cause**: Frequency counting failed or data mismatch
**Solution**: This is a clear error message pointing to the exact issue. Check that:
1. The input file is readable
2. The file isn't corrupted
3. There's enough memory

### Error: "Decode error at position 0"
**Cause**: This was the original bug - SHOULD BE FIXED NOW
**Solution**: If you still see this after applying the fixes, please:
1. Verify you rebuilt the project: `./gradlew clean build`
2. Check that CanonicalHuffman.java contains `Math.max(1, depth)`
3. Report the issue with the specific file that fails

### Error: "File too large"
**Cause**: File exceeds maximum chunks (Integer.MAX_VALUE)
**Solution**: Increase chunk size with `-Pchunk=128` or `-Pchunk=256`

### Compression ratio too high (> 100%)
**Cause**: File is already compressed or contains random data
**Solution**: This is normal! Compression cannot reduce all file types.

## Verification Commands

### Check Build Status
```bash
./gradlew build
```
Should complete with "BUILD SUCCESSFUL"

### Run Tests
```bash
./gradlew test
```
All tests should pass

### Check for Linting Errors
```bash
./gradlew check
```
Only warning should be about incubating modules (expected)

## Success Criteria

✅ **All fixes are working if**:
1. PDF files compress to 70-95% of original size (not to bytes!)
2. Decompression never fails with "decode error at position 0"
3. Decompressed files are bit-identical to originals (verified with diff/fc)
4. No "silent" failures (all errors are reported clearly)
5. Benchmark metrics show reasonable throughput (50-500 MB/s)

## Need Help?

If you encounter issues:
1. Check `COMPRESSION_FIX_SUMMARY.md` for detailed fix information
2. Review the error messages (they're now much more informative)
3. Check the logs in `logs/` directory for detailed traces
4. Verify you're using the correct file paths and commands

## Quick Start

**Fastest way to test**:
```bash
# 1. Build
./gradlew build

# 2. Create test file
echo "Hello, World! This is a test." > test.txt

# 3. Compress
./gradlew compress -Pinput=test.txt -Poutput=test.dc

# 4. Decompress
./gradlew decompress -Pinput=test.dc -Poutput=test_restored.txt

# 5. Verify
fc test.txt test_restored.txt

# Should output: "FC: no differences encountered"
```

If this works, the fixes are successful! ✅



