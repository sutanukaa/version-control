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
      
      default:
        System.out.println("Unknown command: " + command);
        break;
    }
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
