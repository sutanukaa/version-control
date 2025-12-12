import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
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
      
      default:
        System.out.println("Unknown command: " + command);
        break;
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
