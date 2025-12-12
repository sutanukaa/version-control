import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.InflaterInputStream;

public class Main {
  public static void main(String[] args){
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.err.println("Logs from your program will appear here!");

    final String command = args[0];
    
    switch (command) {
      case "init" -> {
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
      }
      
      case "cat-file" -> {
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
          byte[] decompressedData = decompressStream.readAllBytes();
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
      }
      
      default -> System.out.println("Unknown command: " + command);
    }
  }
}
