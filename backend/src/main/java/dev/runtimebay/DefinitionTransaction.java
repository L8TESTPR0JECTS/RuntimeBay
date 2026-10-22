package dev.runtimebay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** A prepared journal restores all old definitions on exception or on the next startup. */
final class DefinitionTransaction {
    static final List<String> FILES=List.of("projects.json","workflows.json","workflow-manifests.json","workflow-containers.json","workflow-attachments.json");
    private record Journal(int version,String backup,List<String> present){}
    private final Path directory,backup,journal;
    DefinitionTransaction(Path directory)throws IOException {
        this.directory=directory;journal=directory.resolve("manifest-transaction.json");
        if(Files.exists(journal,LinkOption.NOFOLLOW_LINKS))throw new IOException("A manifest import needs recovery. Restart Runtime Bay before editing definitions.");
        backup=directory.resolve("manifest-backup-"+UUID.randomUUID());Files.createDirectory(backup);Files.setPosixFilePermissions(backup,PosixFilePermissions.fromString("rwx------"));
        var present=new ArrayList<String>();
        try {
            for(String name:FILES) {
                Path file=directory.resolve(name);if(Files.isSymbolicLink(file))throw new IOException("Definition files cannot be symbolic links");
                if(Files.exists(file)){write(backup.resolve(name),Files.readAllBytes(file));present.add(name);}
            }
            write(journal,new Gson().toJson(new Journal(1,backup.getFileName().toString(),List.copyOf(present))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(IOException|RuntimeException error){cleanup();throw error;}
    }
    void commit()throws IOException {Files.delete(journal);cleanup();}
    void rollback()throws IOException {recover(directory);}
    static void recover(Path directory)throws IOException {
        Path journal=directory.resolve("manifest-transaction.json");if(!Files.exists(journal,LinkOption.NOFOLLOW_LINKS))return;
        try {
            if(Files.isSymbolicLink(journal))throw new IOException("Invalid import journal");
            var value=new Gson().fromJson(Files.readString(journal),Journal.class);
            if(value==null||value.version()!=1||value.backup()==null||!value.backup().matches("manifest-backup-[0-9a-f-]{36}")||value.present()==null||!FILES.containsAll(value.present())||new HashSet<>(value.present()).size()!=value.present().size())throw new IOException("Invalid import journal");
            Path backup=directory.resolve(value.backup());if(Files.isSymbolicLink(backup)||!Files.isDirectory(backup))throw new IOException("Import backup is unavailable");
            // Verify all backups before restoring any source.
            Map<String,byte[]> originals=new HashMap<>();
            for(String name:value.present()) {Path file=backup.resolve(name);if(Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new IOException("Incomplete import backup");originals.put(name,Files.readAllBytes(file));}
            for(String name:FILES) {
                Path file=directory.resolve(name);if(Files.isSymbolicLink(file))throw new IOException("Definition files cannot be symbolic links");
                if(originals.containsKey(name))write(file,originals.get(name));else Files.deleteIfExists(file);
            }
            Files.delete(journal);
            for(String name:FILES)try{Files.deleteIfExists(backup.resolve(name));}catch(IOException ignored){}
            try{Files.deleteIfExists(backup);}catch(IOException ignored){}
        }catch(IOException error){throw new IOException("YAML import recovery is incomplete. Saved backups and journal are retained; restore them before reopening Runtime Bay.",error);}
        catch(RuntimeException error){throw new IOException("Cannot read the YAML import recovery journal. Existing files are preserved.",error);}
    }
    static void write(Path file,byte[] bytes)throws IOException {
        Path temporary=Files.createTempFile(file.getParent(),"definition-",".tmp");
        try {
            Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    private void cleanup(){for(String name:FILES)try{Files.deleteIfExists(backup.resolve(name));}catch(IOException ignored){}try{Files.deleteIfExists(backup);}catch(IOException ignored){}}
}
