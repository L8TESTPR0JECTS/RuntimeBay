package dev.runtimebay;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Dedicated native bridge to the macOS login keychain; never invokes security with a password argument. */
final class HostKeychain implements HostPasswords.Persistence {
    private final Path helper;
    HostKeychain(Path helper){this.helper=helper;}
    static String account(HostRegistry.Host host){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((host.id()+"\0"+host.address()+"\0"+host.user()+"\0"+host.port()).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public boolean available(){return Files.isExecutable(helper);}
    public boolean has(HostRegistry.Host host){return run("exists",host,null).code()==0;}
    public String read(HostRegistry.Host host){var result=run("read",host,null);if(result.code()==4)return null;if(result.code()!=0)throw unavailable();try{return new String(result.bytes(),StandardCharsets.UTF_8);}finally{Arrays.fill(result.bytes(),(byte)0);}}
    public void write(HostRegistry.Host host,String password){if(run("write",host,password).code()!=0)throw unavailable();}
    public void delete(HostRegistry.Host host){if(run("delete",host,null).code()!=0)throw unavailable();}
    private record Result(int code,byte[] bytes){}
    private Result run(String operation,HostRegistry.Host host,String password){
        if(!available())throw new ApiException(503,"The macOS Keychain helper is unavailable. Rebuild Runtime Bay.");
        Process child=null;byte[] input=password==null?null:password.getBytes(StandardCharsets.UTF_8);
        try{
            var builder=new ProcessBuilder(helper.toAbsolutePath().toString(),operation,account(host));builder.environment().keySet().removeIf(k->k.startsWith("RUNTIMEBAY_"));builder.redirectError(ProcessBuilder.Redirect.DISCARD);child=builder.start();
            try(var out=child.getOutputStream()){if(input!=null)out.write(input);}
            // The helper returns at most 16 KiB. Drain before wait to avoid a pipe-buffer deadlock.
            final Process process=child;
            var reader=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return process.getInputStream().readNBytes(16385);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
            if(!child.waitFor(20,TimeUnit.SECONDS))throw unavailable();byte[] bytes=reader.get(2,TimeUnit.SECONDS);if(bytes.length>16384)throw unavailable();return new Result(child.exitValue(),bytes);
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception error){throw unavailable();}
        finally{if(input!=null)Arrays.fill(input,(byte)0);if(child!=null)child.destroyForcibly();}
    }
    private static ApiException unavailable(){return new ApiException(503,"macOS Keychain is locked, access was denied, or the credential is unavailable. Unlock your login keychain, or enter a session password and retry.");}
}
