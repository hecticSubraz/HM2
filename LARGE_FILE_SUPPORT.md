# Large File Support (Up to 30 GB)

## Overview

The DataComp compression system now supports efficient compression and decompression of large files up to 30 GB+ using **stream-based chunk processing** and **memory-efficient algorithms**.

## Key Features

### ✅ Fixed Issues
1. **No more 0 KB output files** - Comprehensive error handling and validation
2. **Memory efficient** - Constant memory usage regardless of file size
3. **Stream-based processing** - No full-file loading into memory
4. **Robust error handling** - Detailed logging and automatic cleanup
5. **Data integrity** - SHA-256 checksums verify every chunk
6. **Progress tracking** - Real-time progress updates for large files

### 📊 Performance Characteristics

| File Size | Chunks (512MB) | Memory Usage | Est. Time (60MB/s) |
|-----------|----------------|--------------|---------------------|
| 1 GB      | 2              | ~600 MB      | ~17 seconds         |
| 5 GB      | 10             | ~600 MB      | ~84 seconds         |
| 10 GB     | 20             | ~600 MB      | ~168 seconds        |
| 30 GB     | 59             | ~600 MB      | ~504 seconds        |

## Architecture

### Two-Phase Compression

**Phase 1: Chunk Processing**
```
Input File → Read Chunk → Compute Huffman Codes → Compress → Write to Temp File
             (512 MB)                                           (Sequential)
```

**Phase 2: Final Assembly**
```
Temp File → Prepend Header → Final Compressed File
            (Metadata)        (Complete)
```

### Benefits of Two-Phase Approach
- ✅ Memory usage stays constant (one chunk at a time)
- ✅ Enables pre-computation of offsets for random access
- ✅ Allows validation before finalizing output
- ✅ Temp file automatically cleaned up on error

## Configuration

### Recommended Settings for Large Files

**In `application.conf`:**
```conf
datacomp {
    compression {
        # For files > 5GB, use larger chunks
        chunk-size-mb = 512  # or 1024 for very large files
    }
}
```

**JVM Settings (already configured in build.gradle):**
```
-Xms512m  # Initial heap
-Xmx8g    # Maximum heap (8GB for large file support)
```

### Chunk Size Guidelines

| File Size Range | Recommended Chunk Size | Reason                        |
|-----------------|------------------------|-------------------------------|
| < 1 GB          | 256-512 MB             | Good balance                  |
| 1-10 GB         | 512 MB                 | Optimal for most cases        |
| 10-30 GB        | 512-1024 MB            | Reduces total chunk count     |
| > 30 GB         | 1024 MB                | Minimizes metadata overhead   |

## Usage

### Compress Large File

```bash
# Using Gradle (recommended)
gradlew compress \
  -Pinput=/path/to/large-file.iso \
  -Poutput=/path/to/large-file.dcz \
  -Pchunk=512

# Or directly via CLI
java -jar datacomp.jar compress large-file.iso large-file.dcz 512
```

### Decompress Large File

```bash
# Using Gradle
gradlew decompress \
  -Pinput=/path/to/large-file.dcz \
  -Poutput=/path/to/large-file-restored.iso

# Or directly via CLI
java -jar datacomp.jar decompress large-file.dcz large-file-restored.iso
```

## Logging and Progress

### Compression Log Output

```
INFO  Compressing ubuntu-22.04.iso (4831838208 bytes, 4.50 GB) into 10 chunks
INFO  Phase 1: Compressing chunks to temporary file...
INFO  Chunk 1/10 compressed: 536870912 -> 498234156 bytes (ratio: 92.81%, 5234ms)
INFO  Chunk 2/10 compressed: 536870912 -> 501456789 bytes (ratio: 93.41%, 5198ms)
...
INFO  Phase 1 complete: 4956789012 bytes compressed to temp file
INFO  Phase 2: Writing final compressed file...
INFO  Header written (10 chunks metadata)
INFO  Copied 4956789012 bytes of compressed data to final file
INFO  Compression complete: 4831838208 -> 4956890123 bytes (102.58%) in 52.45s (92.13 MB/s)
```

### Decompression Log Output

```
INFO  Decompressing ubuntu-22.04.dcz to ubuntu-22.04-restored.iso
INFO  Input file size: 4956890123 bytes (4.62 GB)
INFO  Reading compression header...
INFO  Decompressing 10 chunks, original size: 4831838208 bytes (4.50 GB)
INFO  Decompressing chunk 1/10: offset=0, compressedSize=498234156, originalSize=536870912
INFO  Chunk 1/10 decompressed: 536870912 bytes (4823ms, checksum OK)
...
INFO  Decompression complete: 4831838208 bytes in 48.23s (100.17 MB/s)
INFO  File integrity verified - decompressed 4831838208 bytes successfully
```

## Error Handling

### Common Issues and Solutions

#### 1. Out of Memory Error
**Symptom:** `java.lang.OutOfMemoryError: Java heap space`

**Solution:**
```bash
# Increase heap size in build.gradle (already set to 8GB)
# Or run with custom heap:
java -Xmx12g -jar datacomp.jar compress large-file.iso output.dcz
```

#### 2. Temporary File Issues
**Symptom:** "Temporary compressed file is empty"

**Solution:** Ensure sufficient disk space in the output directory for:
- Original file size
- Compressed file size (potentially larger for incompressible data)
- Temporary file (same as compressed size)

#### 3. Checksum Mismatch
**Symptom:** "Checksum mismatch in chunk X"

**Solution:**
- File may be corrupted during compression
- Disk errors
- Re-compress the file
- Check disk health

## Technical Details

### Memory Management

**Memory Usage Formula:**
```
Total Memory = Chunk Size + Overhead
              = 512 MB + ~100 MB
              = ~600 MB (constant)
```

**Why Constant?**
- Only one chunk in memory at a time
- Huffman tree metadata is small (~few KB per chunk)
- Streaming I/O prevents buffering entire file

### File Format

```
[Header]
  - Magic Number (4 bytes): 0x44435A46 ("DCZF")
  - Version (4 bytes)
  - Original Filename (variable)
  - Original File Size (8 bytes)
  - Timestamp (8 bytes)
  - Chunk Size (4 bytes)
  - Global Checksum (32 bytes SHA-256)
  - Number of Chunks (4 bytes)
  - For each chunk:
    - Chunk Index (4 bytes)
    - Original Offset (8 bytes)
    - Original Size (4 bytes)
    - Compressed Offset (8 bytes)
    - Compressed Size (4 bytes)
    - SHA-256 Checksum (32 bytes)
    - Huffman Code Lengths (256 × 2 bytes = 512 bytes)

[Compressed Data]
  - Chunk 0 data
  - Chunk 1 data
  - ...
  - Chunk N-1 data
```

### Limits

| Limit Type            | Value                      | Notes                           |
|-----------------------|----------------------------|---------------------------------|
| Max File Size         | ~1 million GB              | Limited by Integer.MAX_VALUE    |
| Max Chunk Count       | 2,147,483,647              | Int limit                       |
| Max Chunk Size        | 2,147,483,647 bytes (~2GB) | Int limit                       |
| Practical File Limit  | 30-50 GB                   | Based on processing time        |

## Troubleshooting

### Enable Debug Logging

In `application.conf`:
```conf
datacomp {
    logging {
        level = "DEBUG"  # Changed from "INFO"
    }
}
```

### Verify File Integrity

```bash
# Compress with verification
gradlew compress -Pinput=file.iso -Poutput=file.dcz

# Decompress
gradlew decompress -Pinput=file.dcz -Poutput=file-restored.iso

# Compare checksums (Linux/Mac)
sha256sum file.iso file-restored.iso

# Compare checksums (Windows PowerShell)
Get-FileHash file.iso -Algorithm SHA256
Get-FileHash file-restored.iso -Algorithm SHA256
```

## Best Practices

1. **Use appropriate chunk sizes** - Larger chunks for larger files
2. **Ensure sufficient disk space** - At least 2× file size
3. **Monitor logs** - Check for errors or warnings
4. **Verify integrity** - Always check checksums after decompression
5. **Keep originals** - Don't delete source files until verified
6. **Use SSD if possible** - Faster I/O for large files

## Performance Tuning

### For Maximum Speed

1. **Increase chunk size** to 1024 MB (reduces overhead)
2. **Use SSD** for temp files
3. **Ensure no anti-virus scanning** temp directory
4. **Close other applications** to free memory

### For Maximum Compression

1. **Use default 512 MB chunks** (allows better Huffman optimization per chunk)
2. **Ensure data has patterns** (random data won't compress well)

## Examples

### Compress Ubuntu ISO (4.5 GB)

```bash
cd datacomp
gradlew compress \
  -Pinput="C:\Downloads\ubuntu-22.04.3-desktop-amd64.iso" \
  -Poutput="C:\Backup\ubuntu.dcz" \
  -Pchunk=512
```

Expected time: ~80 seconds at 60 MB/s

### Decompress and Verify

```bash
gradlew decompress \
  -Pinput="C:\Backup\ubuntu.dcz" \
  -Poutput="C:\Restore\ubuntu-restored.iso"

# Verify (PowerShell)
$original = Get-FileHash "C:\Downloads\ubuntu-22.04.3-desktop-amd64.iso"
$restored = Get-FileHash "C:\Restore\ubuntu-restored.iso"
$original.Hash -eq $restored.Hash  # Should output: True
```

## FAQ

**Q: Why is my compressed file larger than the original?**
A: Huffman coding works best on data with patterns. Random or already-compressed data (like JPEG, ZIP) may not compress further.

**Q: Can I resume a failed compression?**
A: Not yet implemented, but planned for future versions.

**Q: How do I compress files larger than 30 GB?**
A: Increase chunk size to 1024 MB and ensure sufficient RAM (8GB+ recommended).

**Q: Is compression multithreaded?**
A: Not yet, but planned. Currently processes one chunk at a time sequentially.

**Q: Can I use GPU acceleration?**
A: Yes, set `gpu.auto-detect = true` in configuration and ensure TornadoVM is installed.

## Support

For issues or questions:
1. Check logs in `logs/datacomp.log`
2. Enable DEBUG logging
3. Review this documentation
4. Check GitHub issues

## Version History

### v1.2.0 (Current)
- ✅ Fixed 0 KB output file issue
- ✅ Added comprehensive logging
- ✅ Improved error handling
- ✅ Support for files up to 30+ GB
- ✅ Memory-efficient streaming
- ✅ Automatic cleanup on errors
- ✅ Enhanced data validation

### v1.1.0
- Added chunk-based processing
- Initial large file support

### v1.0.0
- Initial release
- Small file support only

