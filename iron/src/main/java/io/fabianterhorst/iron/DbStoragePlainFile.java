package io.fabianterhorst.iron;

import android.content.Context;
import android.util.Log;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;

import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.CipherOutputStream;

import io.fabianterhorst.iron.serializer.ArraysAsListSerializer;
import io.fabianterhorst.iron.serializer.NoArgCollectionSerializer;
import io.fabianterhorst.iron.serializer.SynchronizedCollectionsSerializer;
import io.fabianterhorst.iron.serializer.UnmodifiableCollectionsSerializer;

public class DbStoragePlainFile implements Storage {

    private static final String TAG = DbStoragePlainFile.class.getName();
    private final Context mContext;
    private final String mDbName;
    private String mFilesDir;
    private boolean mIronDirIsCreated;

    private final Encryption mEncryption;

    private final Cache mMemoryCache;

    private Kryo getKryo() {
        return mKryo.get();
    }

    private final ThreadLocal<Kryo> mKryo = new ThreadLocal<Kryo>() {
        @Override
        protected Kryo initialValue() {
            return createKryoInstance();
        }
    };

    private Kryo createKryoInstance() {
        Kryo kryo = new Kryo();

        kryo.register(IronTable.class);
        kryo.setDefaultSerializer(CompatibleFieldSerializer.class);
        kryo.setReferences(false);

        // Serialize Arrays$ArrayList
        //noinspection ArraysAsListWithZeroOrOneArgument
        kryo.register(Arrays.asList("").getClass(), new ArraysAsListSerializer());
        UnmodifiableCollectionsSerializer.registerSerializers(kryo);
        SynchronizedCollectionsSerializer.registerSerializers(kryo);
        // Serialize inner AbstractList$SubAbstractListRandomAccess
        kryo.addDefaultSerializer(new ArrayList<>().subList(0, 0).getClass(),
                new NoArgCollectionSerializer());
        // Serialize AbstractList$SubAbstractList
        kryo.addDefaultSerializer(new LinkedList<>().subList(0, 0).getClass(),
                new NoArgCollectionSerializer());
        // To keep backward compatibility don't change the order of serializers above

        kryo.setInstantiatorStrategy(
                new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        return kryo;
    }

    public DbStoragePlainFile(Context context, String dbName, Encryption encryption, int cache) {
        mContext = context;
        mDbName = dbName;
        mEncryption = encryption;
        if (cache == Cache.NONE)
            mMemoryCache = new Cache() {
                @Override
                public void evictAll() {
                    //don not need to clear the cache
                }

                @Override
                public Object put(String key, Object value) {
                    return null;
                }

                @Override
                public Object get(String key) {
                    return doSelect(key);
                }

                @Override
                public Object remove(String key) {
                    return null;
                }
            };
        else
            mMemoryCache = new LruCache() {
                @Override
                public Object create(String key) {
                    return doSelect(key);
                }
            };
    }

    @Override
    public synchronized void destroy() {
        assertInit();

        final String dbPath = getDbPath(mContext, mDbName);
        if (!deleteDirectory(dbPath)) {
            Log.e(TAG, "Couldn't delete Iron dir " + dbPath);
        }
        mMemoryCache.evictAll();
        mIronDirIsCreated = false;
    }

    @Override
    public synchronized <E> void insert(String key, E value) {
        assertInit();

        final IronTable<E> ironTable = new IronTable<>(value);

        final File originalFile = getOriginalFile(key);
        final File backupFile = makeBackupFile(originalFile);
        // Rename the current file so it may be used as a backup during the next read
        if (originalFile.exists()) {
            //Rename original to backup
            if (!backupFile.exists()) {
                if (!originalFile.renameTo(backupFile)) {
                    throw new IronException("Couldn't rename file " + originalFile
                            + " to backup file " + backupFile);
                }
            } else {
                //Backup exist -> original file is broken and must be deleted
                //noinspection ResultOfMethodCallIgnored
                originalFile.delete();
            }
        }

        writeTableFile(key, ironTable, originalFile, backupFile);
        mMemoryCache.put(key, value);
    }

    @Override
    public synchronized <E> E select(String key) {
        try {
            return (E) mMemoryCache.get(key);
        } catch (IllegalStateException e) {
            return null;
        } catch (IronException e) {
            throw e;
        } catch (Exception e) {
            if (e.getCause() != null && e.getCause() instanceof IronException)
                throw (IronException) e.getCause();
            throw new IronException(e);
        }
    }

    @Override
    public <E> E doSelect(String key) {
        assertInit();
        final File originalFile = getOriginalFile(key);
        final File backupFile = makeBackupFile(originalFile);
        if (backupFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            originalFile.delete();
            //noinspection ResultOfMethodCallIgnored
            backupFile.renameTo(originalFile);
        }

        if (!exist(key)) {
            return null;
        }

        return readTableFile(key, originalFile);
    }

    @Override
    public synchronized boolean exist(String key) {
        assertInit();

        final File originalFile = getOriginalFile(key);
        return originalFile.exists();
    }

    @Override
    public List<String> getAllKeys() {
        assertInit();

        File chestFolder = new File(mFilesDir);
        String[] names = chestFolder.list();
        if (names != null) {
            //remove extensions
            for (int i = 0; i < names.length; i++) {
                names[i] = names[i].replace(".pt", "");
            }
            return Arrays.asList(names);
        } else {
            return new ArrayList<>();
        }
    }

    @Override
    public synchronized void deleteIfExists(String key) {
        assertInit();

        final File originalFile = getOriginalFile(key);
        if (!originalFile.exists()) {
            return;
        }

        boolean deleted = originalFile.delete();
        if (!deleted) {
            throw new IronException("Couldn't delete file " + originalFile
                    + " for table " + key);
        }
        mMemoryCache.remove(key);
    }

    private File getOriginalFile(String key) {
        final String tablePath = mFilesDir + File.separator + key + ".pt";
        return new File(tablePath);
    }

    /**
     * Attempt to write the file, delete the backup and return true as atomically as
     * possible.  If any exception occurs, delete the new file; next time we will restore
     * from the backup.
     *
     * @param key          table key
     * @param ironTable    table instance
     * @param originalFile file to write new data
     * @param backupFile   backup file to be used if write is failed
     */
    private <E> void writeTableFile(String key, IronTable<E> ironTable,
                                    File originalFile, File backupFile) {
        try {
            if (mEncryption == null) {
                FileOutputStream outputStream = new FileOutputStream(originalFile);
                final Output kryoOutput = new Output(outputStream);
                getKryo().writeObject(kryoOutput, ironTable);
                kryoOutput.flush();
                outputStream.flush();
                sync(outputStream);
                kryoOutput.close(); //also close file stream
                // Writing was successful, delete the backup file if there is one.
                //noinspection ResultOfMethodCallIgnored
                backupFile.delete();
            } else {
                FileOutputStream fileOutputStream = new FileOutputStream(originalFile);
                final Output kryoOutput = new Output(fileOutputStream);
                CipherOutputStream outputStream = mEncryption.encrypt(kryoOutput);
                Output cipherOutput = new Output(outputStream, 256) {
                    public void close() throws KryoException {
                        // Don't allow the CipherOutputStream to close the output.
                    }
                };

                getKryo().writeObject(cipherOutput, ironTable);

                cipherOutput.flush();
                fileOutputStream.flush();
                sync(fileOutputStream);
                try {
                    outputStream.close();
                } catch (IOException ex) {
                    throw new KryoException(ex);
                }
                kryoOutput.close();
                // Writing was successful, delete the backup file if there is one.
                //noinspection ResultOfMethodCallIgnored
                backupFile.delete();
            }

        } catch (IOException | KryoException e) {
            // Clean up an unsuccessfully written file
            if (originalFile.exists() && !originalFile.delete()) {
                throw new IronException("Couldn't clean up partially-written file "
                        + originalFile, e);
            }
            throw new IronException("Couldn't save table: " + key + ". " +
                    "Backed up table will be used on next read attempt", e);
        }
    }

    private <E> E readTableFile(String key, File originalFile) {
        try {
            final Kryo kryo = getKryo();
            if (mEncryption == null) {
                InputStream inputStream = new FileInputStream(originalFile);
                final Input i = new Input(inputStream);
                //noinspection unchecked
                final IronTable<E> ironTable = kryo.readObject(i, IronTable.class);
                i.close();
                return ironTable.getContent();
            } else {
                FileInputStream fileInputStream = new FileInputStream(originalFile);
                ByteArrayInputStream inputStream = mEncryption.decrypt(fileInputStream);
                Input i = new Input(inputStream == null ? fileInputStream : inputStream, inputStream == null ? 4096 : 256);
                //noinspection unchecked
                final IronTable<E> ironTable = kryo.readObject(i, IronTable.class);
                i.close();
                return ironTable.getContent();
            }
        } catch (KryoException | IllegalArgumentException | IOException e) {
            // Clean up an unsuccessfully written file
            if (originalFile.exists() && !originalFile.delete()) {
                throw new IronException("Couldn't clean up broken/unserializable file "
                        + originalFile, e);
            }
            String errorMessage = "Couldn't read/deserialize file "
                    + originalFile + " for table " + key;
            if (e.getMessage().startsWith("Class cannot be created (missing no-arg constructor): ")) {
                String className = e.getMessage()
                        .replace("Class cannot be created (missing no-arg constructor):", "");
                errorMessage = "You have to add a public no-arg constructor for the class" + className
                        + "\n Read more: https://github.com/fabianterhorst/Iron#save";
            }
            throw new IronException(errorMessage, e);
        }
    }

    private String getDbPath(Context context, String dbName) {
        return context.getFilesDir() + File.separator + dbName;
    }

    private void assertInit() {
        if (!mIronDirIsCreated) {
            createIronDir();
            mIronDirIsCreated = true;
        }
    }

    private void createIronDir() {
        mFilesDir = getDbPath(mContext, mDbName);
        if (!new File(mFilesDir).exists()) {
            boolean isReady = new File(mFilesDir).mkdirs();
            if (!isReady) {
                throw new IronException("Couldn't create Iron dir: " + mFilesDir);
            }
        }
    }

    private static boolean deleteDirectory(String dirPath) {
        File directory = new File(dirPath);
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (null != files) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file.toString());
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }

    private File makeBackupFile(File originalFile) {
        return new File(originalFile.getPath() + ".bak");
    }

    /**
     * Perform an fsync on the given FileOutputStream.  The stream at this
     * point must be flushed but not yet closed.
     */
    private static boolean sync(FileOutputStream stream) {
        //noinspection EmptyCatchBlock
        try {
            if (stream != null) {
                stream.getFD().sync();
            }
            return true;
        } catch (IOException e) {
        }
        return false;
    }

    @Override
    public void invalidateCache() {
        mMemoryCache.evictAll();
    }

    @Override
    public void invalidateCache(String key) {
        mMemoryCache.remove(key);
    }
}

