package net.kdt.pojavlaunch.multirt;

import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;
import static org.apache.commons.io.FileUtils.listFiles;

import android.system.Os;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.JREUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class MultiRTUtils {

    private static final HashMap<String,Runtime> sCache = new HashMap<>();

    private static final File RUNTIME_FOLDER = new File(Tools.MULTIRT_HOME);
    private static final String JAVA_VERSION_STR = "JAVA_VERSION=\"";
    private static final String OS_ARCH_STR = "OS_ARCH=\"";

    public static List<Runtime> getRuntimes() {
        if(!RUNTIME_FOLDER.exists() && !RUNTIME_FOLDER.mkdirs()) {
            throw new RuntimeException("Failed to create runtime directory");
        }

        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = RUNTIME_FOLDER.listFiles();
        if(files != null) for(File f : files) {
            runtimes.add(read(f.getName()));
        }
        else throw new RuntimeException("The runtime directory does not exist");

        return runtimes;
    }

    public static String getExactJreName(int majorVersion) {
        List<Runtime> runtimes = getRuntimes();
        for(Runtime r : runtimes)
            if(r.javaVersion == majorVersion)return r.name;

        return null;
    }

    public static String getNearestJreName(int majorVersion) {
        List<Runtime> runtimes = getRuntimes();
        int diff_factor = Integer.MAX_VALUE;
        String result = null;
        for(Runtime r : runtimes) {
            if(r.javaVersion < majorVersion) continue; // lower - not useful

            int currentFactor = r.javaVersion - majorVersion;
            if(diff_factor > currentFactor) {
                result = r.name;
                diff_factor = currentFactor;
            }
        }

        return result;
    }

    public static void installRuntimeNamed(String nativeLibDir, InputStream runtimeInputStream, String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
        unpack200(nativeLibDir,RUNTIME_FOLDER + "/" + name);
        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
        read(name);
    }

    public static void postPrepare(String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER, name);
        if (!dest.isDirectory()) return;

        Runtime runtime = read(name);
        String libFolder = "lib";
        File archLibDir = new File(dest, libFolder + "/" + runtime.arch);
        if (archLibDir.isDirectory()) libFolder = libFolder + "/" + runtime.arch;

        File libDir = new File(dest, libFolder);
        File ftOut = new File(libDir, "libfreetype.so");
        if (!ftOut.isFile() || ftOut.length() == 0) {
            File ftIn = findFreetypeLibrary(dest);
            if (ftIn == null) {
                String[] entries = libDir.list();
                Log.e("MultiRT", "FreeType missing. Runtime=" + dest.getAbsolutePath()
                        + ", arch=" + runtime.arch + ", libDir=" + libDir.getAbsolutePath()
                        + ", entries=" + java.util.Arrays.toString(entries));
                throw new IOException("Runtime is missing libfreetype.so and its versioned library");
            }

            // Copy rather than rename: keep the versioned SONAME file intact.
            // Some runtime archives store libfreetype.so.6 as a symlink to
            // libfreetype.so.6.x; the recursive search finds the real file.
            if (ftOut.exists() && !ftOut.delete()) {
                throw new IOException("Failed to replace invalid libfreetype.so");
            }
            FileInputStream in = new FileInputStream(ftIn);
            FileOutputStream out = new FileOutputStream(ftOut);
            try {
                IOUtils.copy(in, out);
            } finally {
                in.close();
                out.close();
            }
            Log.i("MultiRT", "Prepared FreeType: " + ftIn.getAbsolutePath()
                    + " -> " + ftOut.getAbsolutePath());
        }

        // Refresh libraries
        copyDummyNativeLib("libawt_xawt.so", dest, libFolder);
    }

    private static File findFreetypeLibrary(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findFreetypeLibrary(child);
                if (found != null) return found;
            } else if (child.isFile()) {
                String fileName = child.getName();
                if (fileName.equals("libfreetype.so.6") || fileName.matches("libfreetype\\.so\\.6\\..+")) {
                    return child;
                }
            }
        }
        return null;
    }

    public static void installRuntimeNamedBinpack(InputStream universalFileInputStream, InputStream platformBinsInputStream, String name, String binpackVersion) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        installRuntimeNamedNoRemove(universalFileInputStream,dest);
        installRuntimeNamedNoRemove(platformBinsInputStream,dest);

        unpack200(NATIVE_LIB_DIR,RUNTIME_FOLDER + "/" + name);

        File binpack_verfile = new File(RUNTIME_FOLDER,"/"+name+"/pojav_version");
        FileOutputStream fos = new FileOutputStream(binpack_verfile);
        fos.write(binpackVersion.getBytes());
        fos.close();

        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);

        forceReread(name);
    }


    public static String __internal__readBinpackVersion(String name) {
        File binpack_verfile = new File(RUNTIME_FOLDER,"/" + name + "/pojav_version");
        try {
            if (binpack_verfile.exists()) {
                return Tools.read(binpack_verfile.getAbsolutePath());
            }else{
                return null;
            }
        }catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void removeRuntimeNamed(String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) {
            FileUtils.deleteDirectory(dest);
            sCache.remove(name);
        }
    }

    public static File getRuntimeHome(String name) {
        File dest = new File(RUNTIME_FOLDER, name);
        Log.i("MiltiRTUitls", "Dest exists? "+dest.exists());
        if((!dest.exists()) || MultiRTUtils.forceReread(name).versionString == null) throw new RuntimeException("Selected runtime is broken!");
        return dest;
    }

    public static Runtime forceReread(String name) {
        sCache.remove(name);
        return read(name);
    }

    public static Runtime read(String name) {
        Runtime returnRuntime = sCache.get(name);
        if(returnRuntime != null) return returnRuntime;
        File release = new File(RUNTIME_FOLDER,name+"/release");
        if(!release.exists()) {
            return new Runtime(name);
        }
        try {
            String content = Tools.read(release.getAbsolutePath());
            String javaVersion = Tools.extractUntilCharacter(content, JAVA_VERSION_STR, '"');
            String osArch = Tools.extractUntilCharacter(content, OS_ARCH_STR, '"');
            if(javaVersion != null && osArch != null) {
                String[] javaVersionSplit = javaVersion.split("\\.");
                int javaVersionInt;
                if (javaVersionSplit[0].equals("1")) {
                    javaVersionInt = Integer.parseInt(javaVersionSplit[1]);
                } else {
                    javaVersionInt = Integer.parseInt(javaVersionSplit[0]);
                }
                returnRuntime = new Runtime(name, javaVersion, osArch, javaVersionInt);
            }else{
                returnRuntime =  new Runtime(name);
            }
        }catch(IOException e) {
            returnRuntime =  new Runtime(name);
        }
        sCache.put(name, returnRuntime);
        return returnRuntime;
    }

    /**
     * Unpacks all .pack files into .jar Serves only for java 8, as java 9 brought project jigsaw
     * @param nativeLibraryDir The native lib path, required to execute the unpack200 binary
     * @param runtimePath The path to the runtime to walk into
     */
    private static void unpack200(String nativeLibraryDir, String runtimePath) {

        File basePath = new File(runtimePath);
        Collection<File> files = listFiles(basePath, new String[]{"pack"}, true);

        File workdir = new File(nativeLibraryDir);

        ProcessBuilder processBuilder = new ProcessBuilder().directory(workdir);
        for(File jarFile : files){
            try{
                Process process = processBuilder.command("./libunpack200.so", "-r", jarFile.getAbsolutePath(), jarFile.getAbsolutePath().replace(".pack", "")).start();
                process.waitFor();
            }catch (InterruptedException | IOException e) {
                Log.e("MULTIRT", "Failed to unpack the runtime !");
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void copyDummyNativeLib(String name, File dest, String libFolder) throws IOException {
        File fileLib = new File(dest, "/"+libFolder + "/" + name);
        FileInputStream is = new FileInputStream(new File(NATIVE_LIB_DIR, name));
        FileOutputStream os = new FileOutputStream(fileLib);
        IOUtils.copy(is, os);
        is.close();
        os.close();
    }

    private static void installRuntimeNamedNoRemove(InputStream runtimeInputStream, File dest) throws IOException {
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
    }

    private static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        if(dest.isFile()) throw new IOException("Attempting to unpack into a file");
        if(!dest.exists() && !dest.mkdirs()) throw new IOException("Failed to create destination directory");

        byte[] buffer = new byte[8192];
        TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new XZCompressorInputStream(tarFileInputStream)
        );
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
        // tarIn is a TarArchiveInputStream
        while (tarEntry != null) {

            final String tarEntryName = tarEntry.getName();
            // publishProgress(null, "Unpacking " + tarEntry.getName());
            ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 100, R.string.global_unpacking, tarEntryName);

            File destPath = new File(dest, tarEntry.getName());
            File destParent = destPath.getParentFile();
            if (tarEntry.isSymbolicLink()) {
                if(destParent != null && !destParent.exists() && !destParent.mkdirs())
                    throw new IOException("Failed to create parent directory for symlink");
                try {
                    // android.system.Os
                    // Libcore one support all Android versions
                    if (destPath.exists() && !destPath.delete()) {
                        throw new IOException("Failed to replace runtime symlink: " + destPath);
                    }
                    Os.symlink(tarEntry.getLinkName(), destPath.getAbsolutePath());
                } catch (Throwable e) {
                    Log.e("MultiRT", e.toString());
                }

            } else if (tarEntry.isDirectory()) {
                if(!destPath.exists() && !destPath.mkdirs())
                    throw new IOException("Failed to create directory");
            } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
                if(destParent != null && !destParent.exists() && !destParent.mkdirs())
                    throw new IOException("Failed to create parent directory for file");

                FileOutputStream os = new FileOutputStream(destPath);
                IOUtils.copyLarge(tarIn, os, buffer);
                os.close();

            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
    }
}
