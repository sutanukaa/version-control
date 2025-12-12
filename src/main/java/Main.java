import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class Main {
  public static void main(String[] args) {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.err.println("Logs from your program will appear here!");

    final String command = args[0];
    
    switch (command) {
      case "init": {
        final File root = new File(".git");
        new File(root, "objects").mkdirs();
        new File(root, "refs").mkdirs();
        final File head = new File(root, "HEAD");
    
        try {
          head.createNewFile();
          Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes());
          System.out.println("Initialized git directory");
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      case "cat-file": {
        // args[1] should be "-p" (we can ignore it for now)
        // args[2] is the blob hash (like "3b18e512dba79e4c8300dd08aeb37f8e728b8dad")
        String blobHash = args[2];
        
        // Step 1: Build the path to the blob file
        // First 2 characters = folder name, rest = file name
        String folderName = blobHash.substring(0, 2);      // "3b"
        String fileName = blobHash.substring(2);            // "18e512dba79e4c8300dd08aeb37f8e728b8dad"
        File blobFile = new File(".git/objects/" + folderName + "/" + fileName);
        
        try {
          // Step 2: Read and decompress the file using zlib
          FileInputStream fileStream = new FileInputStream(blobFile);
          InflaterInputStream decompressStream = new InflaterInputStream(fileStream);
          
          // Read all the decompressed bytes
          byte[] decompressedData = readAllBytes(decompressStream);
          decompressStream.close();
          
          // Step 3: Find the null byte (\0) that separates header from content
          // The format is: "blob <size>\0<content>"
          int nullByteIndex = 0;
          for (int i = 0; i < decompressedData.length; i++) {
            if (decompressedData[i] == 0) {  // Found the null byte!
              nullByteIndex = i;
              break;
            }
          }
          
          // Step 4: Extract just the content (everything after the null byte)
          // Create a string from the bytes starting after the null byte
          String content = new String(
            decompressedData,           // the byte array
            nullByteIndex + 1,          // start position (after the \0)
            decompressedData.length - nullByteIndex - 1  // length of content
          );
          
          // Step 5: Print without adding a newline at the end!
          System.out.print(content);
          
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      case "hash-object": {
        // args[1] should be "-w" (means "write" - actually save the object)
        // args[2] is the file path (like "test.txt")
        String filePath = args[2];
        
        try {
          // Step 1: Read the file content
          File file = new File(filePath);
          byte[] fileContent = Files.readAllBytes(file.toPath());
          
          // Step 2: Create the header: "blob <size>\0"
          // Example: if file has 11 bytes, header is "blob 11\0"
          String header = "blob " + fileContent.length + "\0";
          
          // Step 3: Combine header + content into one byte array
          byte[] headerBytes = header.getBytes();
          byte[] combined = new byte[headerBytes.length + fileContent.length];
          
          // Copy header bytes first
          System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
          // Then copy file content after header
          System.arraycopy(fileContent, 0, combined, headerBytes.length, fileContent.length);
          
          // Step 4: Calculate SHA-1 hash of the combined data
          MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
          byte[] hashBytes = sha1.digest(combined);
          
          // Convert hash bytes to a 40-character hex string
          StringBuilder hexHash = new StringBuilder();
          for (byte b : hashBytes) {
            // Convert each byte to 2 hex characters
            hexHash.append(String.format("%02x", b));
          }
          String hash = hexHash.toString();  // "3b18e512dba79e4c8300dd08aeb37f8e728b8dad"
          
          // Step 5: Create the folder and file path
          String folderName = hash.substring(0, 2);  // "3b"
          String fileName = hash.substring(2);        // "18e512..."
          File objectFolder = new File(".git/objects/" + folderName);
          objectFolder.mkdirs();  // Create the folder if it doesn't exist
          
          File objectFile = new File(objectFolder, fileName);
          
          // Step 6: Compress and write the data using zlib
          FileOutputStream fileOutputStream = new FileOutputStream(objectFile);
          DeflaterOutputStream compressStream = new DeflaterOutputStream(fileOutputStream);
          compressStream.write(combined);  // Write the combined header + content
          compressStream.close();
          
          // Step 7: Print the hash
          System.out.println(hash);
          
        } catch (IOException e) {
          throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      case "ls-tree": {
        // args[1] is "--name-only" (we only print names)
        // args[2] is the tree hash (like "abc123...")
        String treeHash = args[2];
        
        // Step 1: Build the path to the tree object file
        String folderName = treeHash.substring(0, 2);
        String fileName = treeHash.substring(2);
        File treeFile = new File(".git/objects/" + folderName + "/" + fileName);
        
        try {
          // Step 2: Read and decompress the file
          FileInputStream fileStream = new FileInputStream(treeFile);
          InflaterInputStream decompressStream = new InflaterInputStream(fileStream);
          byte[] decompressedData = readAllBytes(decompressStream);
          decompressStream.close();
          
          // Step 3: Skip the header ("tree <size>\0")
          // Find the first null byte to skip past the header
          int position = 0;
          while (decompressedData[position] != 0) {
            position++;
          }
          position++;  // Move past the null byte
          
          // Step 4: Parse each entry in the tree
          // Format: <mode> <name>\0<20-byte-sha>
          List<String> names = new ArrayList<String>();
          
          while (position < decompressedData.length) {
            // Read the mode (like "100644" or "40000")
            // Mode ends with a space
            int modeStart = position;
            while (decompressedData[position] != ' ') {
              position++;
            }
            // String mode = new String(decompressedData, modeStart, position - modeStart);
            position++;  // Skip the space
            
            // Read the name (ends with null byte)
            int nameStart = position;
            while (decompressedData[position] != 0) {
              position++;
            }
            String name = new String(decompressedData, nameStart, position - nameStart);
            names.add(name);
            position++;  // Skip the null byte
            
            // Skip the 20-byte SHA-1 hash (we don't need it for --name-only)
            position += 20;
          }
          
          // Step 5: Print each name on a new line
          for (String name : names) {
            System.out.println(name);
          }
          
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      case "write-tree": {
        // Write the current directory as a tree object
        try {
          String treeHash = writeTree(new File("."));
          System.out.println(treeHash);
        } catch (IOException e) {
          throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      case "commit-tree": {
        // Usage: commit-tree <tree_sha> -p <parent_sha> -m <message>
        // args[0] = "commit-tree"
        // args[1] = tree SHA
        // args[2] = "-p"
        // args[3] = parent SHA
        // args[4] = "-m"
        // args[5] = message
        
        String treeSha = args[1];
        String parentSha = args[3];
        String message = args[5];
        
        try {
          String commitHash = createCommit(treeSha, parentSha, message);
          System.out.println(commitHash);
        } catch (IOException e) {
          throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      case "clone": {
        // Usage: clone <repo_url> <directory>
        String repoUrl = args[1];
        String targetDir = args[2];
        
        try {
          cloneRepository(repoUrl, targetDir);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        break;
      }
      
      default:
        System.out.println("Unknown command: " + command);
        break;
    }
  }
  
  // ============ CLONE IMPLEMENTATION ============
  
  // Store for objects during clone (hash -> decompressed data with header)
  private static Map<String, byte[]> objectStore = new HashMap<String, byte[]>();
  
  /**
   * Clones a repository from a URL to a local directory.
   */
  private static void cloneRepository(String repoUrl, String targetDir) throws Exception {
    // Step 1: Create target directory and initialize git
    File target = new File(targetDir);
    target.mkdirs();
    
    File gitDir = new File(target, ".git");
    new File(gitDir, "objects").mkdirs();
    new File(gitDir, "refs/heads").mkdirs();
    
    // Step 2: Discover refs (get list of branches and their commits)
    String refsUrl = repoUrl + "/info/refs?service=git-upload-pack";
    String headCommit = discoverRefs(refsUrl, gitDir);
    
    // Step 3: Request and receive packfile
    String uploadPackUrl = repoUrl + "/git-upload-pack";
    byte[] packData = fetchPackfile(uploadPackUrl, headCommit);
    
    // Step 4: Parse the packfile and extract objects
    parsePackfile(packData, gitDir);
    
    // Step 5: Checkout the HEAD commit
    checkoutCommit(headCommit, target, gitDir);
  }
  
  /**
   * Discovers refs from the remote repository.
   * Returns the SHA of the HEAD commit.
   */
  private static String discoverRefs(String url, File gitDir) throws Exception {
    URL refsUrl = new URL(url);
    HttpURLConnection conn = (HttpURLConnection) refsUrl.openConnection();
    conn.setRequestMethod("GET");
    
    InputStream is = conn.getInputStream();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int len;
    while ((len = is.read(buffer)) != -1) {
      baos.write(buffer, 0, len);
    }
    is.close();
    
    byte[] data = baos.toByteArray();
    String response = new String(data);
    
    String headCommit = null;
    String headRef = null;
    
    // Parse pkt-lines
    int pos = 0;
    while (pos < data.length) {
      if (pos + 4 > data.length) break;
      
      String lenHex = new String(data, pos, 4);
      if (lenHex.equals("0000")) {
        pos += 4;
        continue;
      }
      
      int pktLen;
      try {
        pktLen = Integer.parseInt(lenHex, 16);
      } catch (NumberFormatException e) {
        pos += 4;
        continue;
      }
      
      if (pktLen <= 4) {
        pos += 4;
        continue;
      }
      
      String content = new String(data, pos + 4, pktLen - 4);
      pos += pktLen;
      
      // Skip service announcement
      if (content.startsWith("#")) continue;
      
      // Parse ref line: <sha> <ref_name>
      // First line has capabilities after null byte
      if (content.length() >= 40) {
        // Find the space after the SHA
        int spaceIdx = content.indexOf(' ');
        if (spaceIdx == 40) {
          String sha = content.substring(0, 40);
          
          // Extract ref name (ends at null byte or end of line)
          String rest = content.substring(41);
          int nullIdx = rest.indexOf('\0');
          String refName;
          if (nullIdx >= 0) {
            refName = rest.substring(0, nullIdx);
          } else {
            refName = rest.trim();
          }
          
          if (refName.equals("HEAD")) {
            headCommit = sha;
          }
          if (refName.equals("refs/heads/master") || refName.equals("refs/heads/main")) {
            headRef = refName;
            if (headCommit == null) {
              headCommit = sha;
            }
          }
        }
      }
    }
    
    // Write HEAD file
    File headFile = new File(gitDir, "HEAD");
    String refToWrite = headRef != null ? headRef : "refs/heads/master";
    Files.write(headFile.toPath(), ("ref: " + refToWrite + "\n").getBytes());
    
    // Write the ref file
    if (headRef != null && headCommit != null) {
      File refFile = new File(gitDir, headRef);
      refFile.getParentFile().mkdirs();
      Files.write(refFile.toPath(), (headCommit + "\n").getBytes());
    }
    
    return headCommit;
  }
  
  /**
   * Fetches the packfile from the server.
   */
  private static byte[] fetchPackfile(String url, String wantCommit) throws Exception {
    URL packUrl = new URL(url);
    HttpURLConnection conn = (HttpURLConnection) packUrl.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/x-git-upload-pack-request");
    conn.setRequestProperty("Accept", "application/x-git-upload-pack-result");
    
    // Build the request body in pkt-line format
    ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
    
    // First want line includes capabilities (NO side-band for simplicity)
    String wantLine = "want " + wantCommit + " no-progress\n";
    writePktLine(requestBody, wantLine);
    
    // Flush packet (0000)
    requestBody.write("0000".getBytes());
    
    // Done
    writePktLine(requestBody, "done\n");
    
    OutputStream os = conn.getOutputStream();
    os.write(requestBody.toByteArray());
    os.close();
    
    // Read response
    InputStream is = conn.getInputStream();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int len;
    while ((len = is.read(buffer)) != -1) {
      baos.write(buffer, 0, len);
    }
    is.close();
    
    byte[] response = baos.toByteArray();
    
    // Find the start of the PACK data (after "PACK" signature)
    int packStart = -1;
    for (int i = 0; i < response.length - 4; i++) {
      if (response[i] == 'P' && response[i+1] == 'A' && response[i+2] == 'C' && response[i+3] == 'K') {
        packStart = i;
        break;
      }
    }
    
    if (packStart == -1) {
      // Debug: show what we got
      System.err.println("Response length: " + response.length);
      if (response.length > 0 && response.length < 500) {
        System.err.println("Response: " + new String(response));
      }
      throw new RuntimeException("Could not find PACK in response");
    }
    
    byte[] packData = new byte[response.length - packStart];
    System.arraycopy(response, packStart, packData, 0, packData.length);
    return packData;
  }
  
  /**
   * Writes a pkt-line to the output stream.
   */
  private static void writePktLine(OutputStream os, String data) throws IOException {
    byte[] bytes = data.getBytes();
    String len = String.format("%04x", bytes.length + 4);
    os.write(len.getBytes());
    os.write(bytes);
  }
  
  /**
   * Parses a packfile and extracts all objects.
   */
  private static void parsePackfile(byte[] packData, File gitDir) throws Exception {
    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packData));
    
    // Read header: PACK
    byte[] magic = new byte[4];
    dis.readFully(magic);
    if (magic[0] != 'P' || magic[1] != 'A' || magic[2] != 'C' || magic[3] != 'K') {
      throw new RuntimeException("Invalid pack header");
    }
    
    // Read version (4 bytes, big-endian)
    int version = dis.readInt();
    
    // Read number of objects (4 bytes, big-endian)
    int numObjects = dis.readInt();
    
    // Track position in the stream
    int position = 12;  // After header
    
    // Parse each object
    for (int i = 0; i < numObjects; i++) {
      position = parsePackObject(packData, position, gitDir);
    }
  }
  
  /**
   * Parses a single object from the packfile.
   * Returns the new position after the object.
   */
  private static int parsePackObject(byte[] packData, int offset, File gitDir) throws Exception {
    int position = offset;
    
    // Read the type and size (variable-length encoding)
    int firstByte = packData[position++] & 0xFF;
    int type = (firstByte >> 4) & 0x7;
    long size = firstByte & 0x0F;
    int shift = 4;
    
    while ((firstByte & 0x80) != 0) {
      firstByte = packData[position++] & 0xFF;
      size |= ((long)(firstByte & 0x7F)) << shift;
      shift += 7;
    }
    
    // Types: 1=commit, 2=tree, 3=blob, 4=tag, 6=ofs_delta, 7=ref_delta
    String typeStr;
    byte[] objectData;
    
    if (type == 6) {
      // OFS_DELTA: offset to base object
      int deltaOffset = 0;
      int b = packData[position++] & 0xFF;
      deltaOffset = b & 0x7F;
      while ((b & 0x80) != 0) {
        b = packData[position++] & 0xFF;
        deltaOffset = ((deltaOffset + 1) << 7) | (b & 0x7F);
      }
      
      // Decompress delta data
      byte[] deltaData = decompressAtPosition(packData, position);
      position += getCompressedSize(packData, position);
      
      // Find base object
      int baseOffset = offset - deltaOffset;
      byte[] baseData = getObjectAtOffset(packData, baseOffset);
      
      // Apply delta
      objectData = applyDelta(baseData, deltaData);
      typeStr = getTypeAtOffset(packData, baseOffset);
      
    } else if (type == 7) {
      // REF_DELTA: SHA of base object
      byte[] baseSha = new byte[20];
      System.arraycopy(packData, position, baseSha, 0, 20);
      position += 20;
      String baseShaHex = bytesToHexString(baseSha);
      
      // Decompress delta data
      byte[] deltaData = decompressAtPosition(packData, position);
      position += getCompressedSize(packData, position);
      
      // Get base object from store
      byte[] baseWithHeader = objectStore.get(baseShaHex);
      if (baseWithHeader == null) {
        throw new RuntimeException("Base object not found: " + baseShaHex);
      }
      
      // Extract base data (skip header)
      int nullIndex = 0;
      while (baseWithHeader[nullIndex] != 0) nullIndex++;
      byte[] baseData = new byte[baseWithHeader.length - nullIndex - 1];
      System.arraycopy(baseWithHeader, nullIndex + 1, baseData, 0, baseData.length);
      
      // Get type from base
      String headerStr = new String(baseWithHeader, 0, nullIndex);
      typeStr = headerStr.split(" ")[0];
      
      // Apply delta
      objectData = applyDelta(baseData, deltaData);
      
    } else {
      // Regular object
      switch (type) {
        case 1: typeStr = "commit"; break;
        case 2: typeStr = "tree"; break;
        case 3: typeStr = "blob"; break;
        case 4: typeStr = "tag"; break;
        default: throw new RuntimeException("Unknown type: " + type);
      }
      
      // Decompress object data
      objectData = decompressAtPosition(packData, position);
      position += getCompressedSize(packData, position);
    }
    
    // Create the full object with header
    String header = typeStr + " " + objectData.length + "\0";
    byte[] headerBytes = header.getBytes();
    byte[] fullObject = new byte[headerBytes.length + objectData.length];
    System.arraycopy(headerBytes, 0, fullObject, 0, headerBytes.length);
    System.arraycopy(objectData, 0, fullObject, headerBytes.length, objectData.length);
    
    // Calculate hash and store
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    byte[] hashBytes = sha1.digest(fullObject);
    String hash = bytesToHexString(hashBytes);
    
    // Store in memory and write to disk
    objectStore.put(hash, fullObject);
    writeObjectRaw(hash, fullObject, gitDir);
    
    return position;
  }
  
  /**
   * Gets the object data at a given offset in the packfile.
   */
  private static byte[] getObjectAtOffset(byte[] packData, int offset) throws Exception {
    int position = offset;
    
    int firstByte = packData[position++] & 0xFF;
    int type = (firstByte >> 4) & 0x7;
    long size = firstByte & 0x0F;
    int shift = 4;
    
    while ((firstByte & 0x80) != 0) {
      firstByte = packData[position++] & 0xFF;
      size |= ((long)(firstByte & 0x7F)) << shift;
      shift += 7;
    }
    
    if (type == 6) {
      // OFS_DELTA
      int deltaOffset = 0;
      int b = packData[position++] & 0xFF;
      deltaOffset = b & 0x7F;
      while ((b & 0x80) != 0) {
        b = packData[position++] & 0xFF;
        deltaOffset = ((deltaOffset + 1) << 7) | (b & 0x7F);
      }
      
      byte[] deltaData = decompressAtPosition(packData, position);
      int baseOffset = offset - deltaOffset;
      byte[] baseData = getObjectAtOffset(packData, baseOffset);
      return applyDelta(baseData, deltaData);
      
    } else if (type == 7) {
      // REF_DELTA
      byte[] baseSha = new byte[20];
      System.arraycopy(packData, position, baseSha, 0, 20);
      position += 20;
      String baseShaHex = bytesToHexString(baseSha);
      
      byte[] deltaData = decompressAtPosition(packData, position);
      byte[] baseWithHeader = objectStore.get(baseShaHex);
      
      int nullIndex = 0;
      while (baseWithHeader[nullIndex] != 0) nullIndex++;
      byte[] baseData = new byte[baseWithHeader.length - nullIndex - 1];
      System.arraycopy(baseWithHeader, nullIndex + 1, baseData, 0, baseData.length);
      
      return applyDelta(baseData, deltaData);
    } else {
      return decompressAtPosition(packData, position);
    }
  }
  
  /**
   * Gets the type string for an object at a given offset.
   */
  private static String getTypeAtOffset(byte[] packData, int offset) throws Exception {
    int firstByte = packData[offset] & 0xFF;
    int type = (firstByte >> 4) & 0x7;
    
    if (type == 6 || type == 7) {
      // Delta - recursively find base type
      int position = offset + 1;
      while ((packData[position - 1] & 0x80) != 0) {
        position++;
      }
      
      if (type == 6) {
        int deltaOffset = 0;
        int b = packData[position++] & 0xFF;
        deltaOffset = b & 0x7F;
        while ((b & 0x80) != 0) {
          b = packData[position++] & 0xFF;
          deltaOffset = ((deltaOffset + 1) << 7) | (b & 0x7F);
        }
        return getTypeAtOffset(packData, offset - deltaOffset);
      } else {
        byte[] baseSha = new byte[20];
        System.arraycopy(packData, position, baseSha, 0, 20);
        String baseShaHex = bytesToHexString(baseSha);
        byte[] baseWithHeader = objectStore.get(baseShaHex);
        int nullIndex = 0;
        while (baseWithHeader[nullIndex] != 0) nullIndex++;
        return new String(baseWithHeader, 0, nullIndex).split(" ")[0];
      }
    }
    
    switch (type) {
      case 1: return "commit";
      case 2: return "tree";
      case 3: return "blob";
      case 4: return "tag";
      default: throw new RuntimeException("Unknown type: " + type);
    }
  }
  
  /**
   * Decompresses zlib data at the given position.
   */
  private static byte[] decompressAtPosition(byte[] data, int offset) throws Exception {
    Inflater inflater = new Inflater();
    inflater.setInput(data, offset, data.length - offset);
    
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    
    while (!inflater.finished()) {
      int count = inflater.inflate(buffer);
      if (count == 0 && inflater.needsInput()) {
        break;
      }
      baos.write(buffer, 0, count);
    }
    
    inflater.end();
    return baos.toByteArray();
  }
  
  /**
   * Gets the compressed size of zlib data at the given position.
   */
  private static int getCompressedSize(byte[] data, int offset) throws Exception {
    Inflater inflater = new Inflater();
    inflater.setInput(data, offset, data.length - offset);
    
    byte[] buffer = new byte[1024];
    while (!inflater.finished()) {
      int count = inflater.inflate(buffer);
      if (count == 0 && inflater.needsInput()) {
        break;
      }
    }
    
    int totalIn = (int) inflater.getTotalIn();
    inflater.end();
    return totalIn;
  }
  
  /**
   * Applies a delta to a base object.
   */
  private static byte[] applyDelta(byte[] base, byte[] delta) throws Exception {
    int position = 0;
    
    // Read base size (variable-length)
    long baseSize = 0;
    int shift = 0;
    int b;
    do {
      b = delta[position++] & 0xFF;
      baseSize |= ((long)(b & 0x7F)) << shift;
      shift += 7;
    } while ((b & 0x80) != 0);
    
    // Read result size (variable-length)
    long resultSize = 0;
    shift = 0;
    do {
      b = delta[position++] & 0xFF;
      resultSize |= ((long)(b & 0x7F)) << shift;
      shift += 7;
    } while ((b & 0x80) != 0);
    
    // Apply delta instructions
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    
    while (position < delta.length) {
      int cmd = delta[position++] & 0xFF;
      
      if ((cmd & 0x80) != 0) {
        // Copy from base
        int copyOffset = 0;
        int copySize = 0;
        
        if ((cmd & 0x01) != 0) copyOffset |= (delta[position++] & 0xFF);
        if ((cmd & 0x02) != 0) copyOffset |= (delta[position++] & 0xFF) << 8;
        if ((cmd & 0x04) != 0) copyOffset |= (delta[position++] & 0xFF) << 16;
        if ((cmd & 0x08) != 0) copyOffset |= (delta[position++] & 0xFF) << 24;
        
        if ((cmd & 0x10) != 0) copySize |= (delta[position++] & 0xFF);
        if ((cmd & 0x20) != 0) copySize |= (delta[position++] & 0xFF) << 8;
        if ((cmd & 0x40) != 0) copySize |= (delta[position++] & 0xFF) << 16;
        
        if (copySize == 0) copySize = 0x10000;
        
        result.write(base, copyOffset, copySize);
        
      } else if (cmd > 0) {
        // Insert new data
        result.write(delta, position, cmd);
        position += cmd;
      }
    }
    
    return result.toByteArray();
  }
  
  /**
   * Writes an object to .git/objects with the given raw data.
   */
  private static void writeObjectRaw(String hash, byte[] data, File gitDir) throws IOException {
    String folderName = hash.substring(0, 2);
    String fileName = hash.substring(2);
    
    File objectFolder = new File(gitDir, "objects/" + folderName);
    objectFolder.mkdirs();
    
    File objectFile = new File(objectFolder, fileName);
    
    FileOutputStream fos = new FileOutputStream(objectFile);
    DeflaterOutputStream dos = new DeflaterOutputStream(fos);
    dos.write(data);
    dos.close();
  }
  
  /**
   * Checks out the given commit to the working directory.
   */
  private static void checkoutCommit(String commitHash, File targetDir, File gitDir) throws Exception {
    // Read commit object
    byte[] commitData = objectStore.get(commitHash);
    if (commitData == null) {
      throw new RuntimeException("Commit not found: " + commitHash);
    }
    
    // Parse commit to find tree hash
    String commitContent = new String(commitData);
    int treeStart = commitContent.indexOf("tree ") + 5;
    String treeHash = commitContent.substring(treeStart, treeStart + 40);
    
    // Checkout the tree
    checkoutTree(treeHash, targetDir);
  }
  
  /**
   * Recursively checks out a tree to the given directory.
   */
  private static void checkoutTree(String treeHash, File directory) throws Exception {
    byte[] treeData = objectStore.get(treeHash);
    if (treeData == null) {
      throw new RuntimeException("Tree not found: " + treeHash);
    }
    
    // Skip header (find null byte)
    int position = 0;
    while (treeData[position] != 0) position++;
    position++;  // Skip null byte
    
    // Parse entries
    while (position < treeData.length) {
      // Read mode
      int modeStart = position;
      while (treeData[position] != ' ') position++;
      String mode = new String(treeData, modeStart, position - modeStart);
      position++;  // Skip space
      
      // Read name
      int nameStart = position;
      while (treeData[position] != 0) position++;
      String name = new String(treeData, nameStart, position - nameStart);
      position++;  // Skip null byte
      
      // Read SHA (20 bytes)
      byte[] shaBytes = new byte[20];
      System.arraycopy(treeData, position, shaBytes, 0, 20);
      position += 20;
      String sha = bytesToHexString(shaBytes);
      
      File entryFile = new File(directory, name);
      
      if (mode.equals("40000")) {
        // Directory
        entryFile.mkdirs();
        checkoutTree(sha, entryFile);
      } else {
        // File
        byte[] blobData = objectStore.get(sha);
        if (blobData == null) {
          throw new RuntimeException("Blob not found: " + sha);
        }
        
        // Skip blob header
        int blobPos = 0;
        while (blobData[blobPos] != 0) blobPos++;
        blobPos++;
        
        byte[] content = new byte[blobData.length - blobPos];
        System.arraycopy(blobData, blobPos, content, 0, content.length);
        
        Files.write(entryFile.toPath(), content);
      }
    }
  }
  
  // ============ HELPER METHODS ============
  
  /**
   * Creates a commit object and writes it to .git/objects.
   * Returns the 40-character SHA-1 hash.
   */
  private static String createCommit(String treeSha, String parentSha, String message) 
      throws IOException, NoSuchAlgorithmException {
    
    // Build the commit content (the part after the header)
    // Format:
    // tree <tree_sha>\n
    // parent <parent_sha>\n
    // author <name> <<email>> <timestamp> <timezone>\n
    // committer <name> <<email>> <timestamp> <timezone>\n
    // \n
    // <message>\n
    
    StringBuilder content = new StringBuilder();
    content.append("tree ").append(treeSha).append("\n");
    content.append("parent ").append(parentSha).append("\n");
    
    // Use current timestamp (seconds since epoch)
    long timestamp = System.currentTimeMillis() / 1000;
    String authorInfo = "John Doe <john@example.com> " + timestamp + " +0000";
    
    content.append("author ").append(authorInfo).append("\n");
    content.append("committer ").append(authorInfo).append("\n");
    content.append("\n");  // Blank line before message
    content.append(message).append("\n");
    
    // Create the full object with header
    byte[] contentBytes = content.toString().getBytes();
    String header = "commit " + contentBytes.length + "\0";
    byte[] headerBytes = header.getBytes();
    
    // Combine header + content
    byte[] fullObject = new byte[headerBytes.length + contentBytes.length];
    System.arraycopy(headerBytes, 0, fullObject, 0, headerBytes.length);
    System.arraycopy(contentBytes, 0, fullObject, headerBytes.length, contentBytes.length);
    
    // Calculate SHA-1 hash
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    byte[] hashBytes = sha1.digest(fullObject);
    String hash = bytesToHexString(hashBytes);
    
    // Write to .git/objects
    writeObject(hash, fullObject);
    
    return hash;
  }
  
  /**
   * Recursively writes a directory as a tree object.
   * Returns the 40-character SHA-1 hash of the tree.
   */
  private static String writeTree(File directory) throws IOException, NoSuchAlgorithmException {
    // Get all files and folders in this directory
    File[] entries = directory.listFiles();
    
    if (entries == null) {
      entries = new File[0];
    }
    
    // Sort entries alphabetically by name (Git requires this!)
    Arrays.sort(entries, new Comparator<File>() {
      public int compare(File a, File b) {
        return a.getName().compareTo(b.getName());
      }
    });
    
    // Build the tree content (without header)
    ByteArrayOutputStream treeContent = new ByteArrayOutputStream();
    
    for (File entry : entries) {
      String name = entry.getName();
      
      // Skip the .git directory!
      if (name.equals(".git")) {
        continue;
      }
      
      String mode;
      byte[] shaBytes;
      
      if (entry.isFile()) {
        // It's a file - create a blob and get its hash
        mode = "100644";  // Regular file mode
        String blobHash = writeBlob(entry);
        shaBytes = hexStringToBytes(blobHash);
      } else if (entry.isDirectory()) {
        // It's a directory - recursively create a tree
        mode = "40000";  // Directory mode (NOT 040000!)
        String subTreeHash = writeTree(entry);
        shaBytes = hexStringToBytes(subTreeHash);
      } else {
        // Skip special files
        continue;
      }
      
      // Write entry: <mode> <name>\0<20-byte-sha>
      treeContent.write((mode + " " + name).getBytes());
      treeContent.write(0);  // Null byte
      treeContent.write(shaBytes);  // 20 bytes of SHA
    }
    
    // Now create the full tree object with header
    byte[] content = treeContent.toByteArray();
    String header = "tree " + content.length + "\0";
    byte[] headerBytes = header.getBytes();
    
    // Combine header + content
    byte[] fullObject = new byte[headerBytes.length + content.length];
    System.arraycopy(headerBytes, 0, fullObject, 0, headerBytes.length);
    System.arraycopy(content, 0, fullObject, headerBytes.length, content.length);
    
    // Calculate SHA-1 hash
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    byte[] hashBytes = sha1.digest(fullObject);
    String hash = bytesToHexString(hashBytes);
    
    // Write to .git/objects
    writeObject(hash, fullObject);
    
    return hash;
  }
  
  /**
   * Creates a blob object from a file and writes it to .git/objects.
   * Returns the 40-character SHA-1 hash.
   */
  private static String writeBlob(File file) throws IOException, NoSuchAlgorithmException {
    // Read file content
    byte[] fileContent = Files.readAllBytes(file.toPath());
    
    // Create header
    String header = "blob " + fileContent.length + "\0";
    byte[] headerBytes = header.getBytes();
    
    // Combine header + content
    byte[] fullObject = new byte[headerBytes.length + fileContent.length];
    System.arraycopy(headerBytes, 0, fullObject, 0, headerBytes.length);
    System.arraycopy(fileContent, 0, fullObject, headerBytes.length, fileContent.length);
    
    // Calculate SHA-1 hash
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    byte[] hashBytes = sha1.digest(fullObject);
    String hash = bytesToHexString(hashBytes);
    
    // Write to .git/objects
    writeObject(hash, fullObject);
    
    return hash;
  }
  
  /**
   * Writes an object to .git/objects/<first2chars>/<rest>
   */
  private static void writeObject(String hash, byte[] data) throws IOException {
    String folderName = hash.substring(0, 2);
    String fileName = hash.substring(2);
    
    File objectFolder = new File(".git/objects/" + folderName);
    objectFolder.mkdirs();
    
    File objectFile = new File(objectFolder, fileName);
    
    // Compress and write
    FileOutputStream fos = new FileOutputStream(objectFile);
    DeflaterOutputStream dos = new DeflaterOutputStream(fos);
    dos.write(data);
    dos.close();
  }
  
  /**
   * Converts a byte array to a hex string.
   * Example: [0x3b, 0x18] -> "3b18"
   */
  private static String bytesToHexString(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
  
  /**
   * Converts a hex string to a byte array.
   * Example: "3b18" -> [0x3b, 0x18]
   */
  private static byte[] hexStringToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
  }
  
  // Helper method to read all bytes from an InputStream (Java 8 compatible)
  private static byte[] readAllBytes(InputStream inputStream) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[1024];
    int bytesRead;
    while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, bytesRead);
    }
    return buffer.toByteArray();
  }
}
