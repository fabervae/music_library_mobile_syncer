package main;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.id3.ID3v23Frames;
import org.jaudiotagger.tag.images.Artwork;

import data.DataClass;
import data.DoubleWrapper;
import util.MurmurHash3;

public class MusicSyncer {
    private final File srcFolder;
    private final File dstFolder;
    private boolean optionAddNewMusic;
    private boolean optionDeleteOrphanedMusic;
    private boolean optionSearchInSubdirectories;
    private final SimpleAttributeSet attr;
    /**
     * We want to make a list of keys to avoid duplication and reduce the
     * likelihood of the programmer forgetting to check for a key. This list
     * will NOT be able to be modified after creation as this will add bugs.
     */
    private final List<FieldKey> listOfFieldKeys;
    
    public MusicSyncer(String strSrcFolder, String strDstFolder) {
        srcFolder = new File(strSrcFolder.replace('\\', '/'));
        dstFolder = new File(strDstFolder.replace("\\", "/"));
        // Options are false by default.
        optionAddNewMusic = false;
        optionDeleteOrphanedMusic = false;
        optionSearchInSubdirectories = false;
        
        attr = new SimpleAttributeSet();
        listOfFieldKeys = Collections.unmodifiableList(Arrays.asList(
                FieldKey.TITLE, FieldKey.ARTIST,
                FieldKey.ALBUM_ARTIST,
                FieldKey.ALBUM, FieldKey.YEAR,
                FieldKey.TRACK, FieldKey.DISC_NO,
                FieldKey.GENRE, FieldKey.COMPOSER));
    }
    
    /**
     * This method is used to start the whole syncing process. It consists of
     * three parts: building a list of modified music, determining what metadata
     * was changed, and making the appropriate updates.
     * 
     * @throws InterruptedException
     */
    public void initiate() throws InterruptedException {
        if (srcFolder.isDirectory() && dstFolder.isDirectory()) {
            DoubleWrapper<List<File>, List<File>> tuppleModifiedNewMusic = buildMusicListToSyncAndDeleteOldFiles(srcFolder);
            updateMetaData(srcFolder, tuppleModifiedNewMusic.getArg1(), tuppleModifiedNewMusic.getArg2());
            addNewMusicList(srcFolder, tuppleModifiedNewMusic.getArg2());
        } else {
            StyleConstants.setForeground(attr, DataClass.ERROR_COLOR);
            UI.writeStatusMessage("ERROR: The source/target folder is not a folder or does not exist.", attr);
        }
    }

    /**
     * This method builds a list of music which has been modified since last
     * sync session. Note that this means that if the program cannot find a
     * previous session file, ALL music will be marked as modified until the
     * metadata is examined closely.
     * 
     * @param currentSrcFolder
     *            the current source folder. Used to handle nested folders.
     * @return a tuple of lists containing 1) a list of modified music and 2) a
     *         list of new music.
     * @throws InterruptedException
     */
    public DoubleWrapper<List<File>, List<File>> buildMusicListToSyncAndDeleteOldFiles(File currentSrcFolder) throws InterruptedException {
        final File[] listOfSrc = srcFolder.listFiles();
        final File[] listOfDst = dstFolder.listFiles();
        // TODO This call here is useless when it comes to nested folders...
        UI.setMaximumLimitOnProgressBar((listOfSrc.length + listOfDst.length));
        // A list with only the modified music.
        final List<File> sortedListOfSrc = new ArrayList<>();
        // A list with only the music to be added.
        final List<File> listOfNewMusic = new ArrayList<>();
        final StringBuilder currentSession = new StringBuilder();
        final List<DoubleWrapper<String, Long>> lastSession = tryToLoadPreviousSession();
        
        StyleConstants.setForeground(attr, DataClass.INFO_COLOR);
        UI.writeStatusMessage("List of src and dst folders completed.", attr);
        // Before we begin, we might want to check if there is any orphaned
        // music that we can get rid of to avoid extra comparisons.
        if (optionDeleteOrphanedMusic) {
            UI.writeStatusMessage("Locating orphaned music...", attr);
            String folderSrcPath = currentSrcFolder.getAbsolutePath();
            lookForAndDeleteOrphanedMusicInDst(listOfDst, folderSrcPath);
        }
        StyleConstants.setForeground(attr, DataClass.INFO_COLOR);
        UI.writeStatusMessage("Finding files in src which have been updated since last session...", attr);
        /*
         * We use a separate index for the last session file because we cannot
         * guarantee that the amount of music in the source folder equals the
         * amount described in the file.
         */
        int lastSessionIndex = 0;
        // The algorithm
        for (int i = 0; i < listOfSrc.length; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            final File fileEntrySrc = listOfSrc[i];
            if (optionSearchInSubdirectories && fileEntrySrc.isDirectory()) {
                // If a folder was found, and the user wants it, then search it.
                // TODO Will not be tested with the current directory
                System.out.println("Recursing through folder; THIS SHOULD NOT HAPPEN (FOR NOW)");
                // Take a backup of the current location, recurse the folder, and restore the original folder when done.
                File currentSrcFolderCopy = currentSrcFolder;
                currentSrcFolder = fileEntrySrc;
                buildMusicListToSyncAndDeleteOldFiles(currentSrcFolder);
                currentSrcFolder = currentSrcFolderCopy;
                continue;
            }
            // Get file name and extension, if any.
            final String strFile = fileEntrySrc.getName();
            final int fileExtIndex = strFile.lastIndexOf("."); // If there is no extension, this will default to -1.
            final String strExt = strFile.substring(fileExtIndex + 1); 
            // We use MurmurHash3 on the file size to get a unique hash value to compare with.
            byte[] fileEntrySrcBytes = null;
            try {
                fileEntrySrcBytes = Files.readAllBytes(fileEntrySrc.toPath());
            } catch (IOException e) { // Will occur if the synchronization is stopped abruptly. 
                System.err.println("FATAL: Could not read bytes of " + strFile + " for hashing.");
                e.printStackTrace();
            }
            final int fileHash = MurmurHash3.murmurhash3_x86_32(fileEntrySrcBytes, 0, strFile.length(), 14);
            //final long fileLastMod = fileEntrySrc.lastModified();
            // To make it case-insensitive (and avoid problems with Turkish), convert to uppercase.
            switch (strExt.toUpperCase()) {
            // Exploit the concept of fallthrough.
            case "MP3":
            case "M4A":
                // Try to locate the file in the previous session instead of checking all the metadata.
                updateCurrentSession(currentSession, strFile, fileHash);
                boolean hasBeenModified = true;
                boolean wasFileLocated = false;
                if (lastSessionIndex < lastSession.size()) {
                    final boolean isSameFile = lastSession.get(lastSessionIndex).getArg1().equals(strFile);
                    hasBeenModified = lastSession.get(lastSessionIndex).getArg2() != fileHash;
                    // We assume that, for the most part, most music is unchanged from last session.
                    if (!isSameFile) {
                        /*
                         * Although it is not the same file, we have to make
                         * absolutely sure that it is not further down the last
                         * session file.
                         */
                        DoubleWrapper<Boolean, Long> locateFileWrapper = tryToLocateFileInPreviouSession(lastSession, lastSessionIndex, strFile);
                        wasFileLocated = locateFileWrapper.getArg1();
                        hasBeenModified = locateFileWrapper.getArg2() != fileHash;
                    } else {
                        wasFileLocated = true;
                    }
                    if (wasFileLocated) {
                        /*
                         * If and only if the file was located, then we can move
                         * on to the next file entry. This is to avoid skipping
                         * a file and prematurely finish the last session.
                         */
                        lastSessionIndex++;
                    }
                }
                // Check if the music exists in dst.
                File fileInDst = new File(dstFolder.getAbsolutePath() + "\\" + strFile);
                if (wasFileLocated && !hasBeenModified) {
                    UI.updateProgressBar(2);
                    continue;
                } else if (optionAddNewMusic && !fileInDst.exists()) {
                    /*
                     * If the option was checked, "mark" new music by adding
                     * them to a list whose contents will be added later. This
                     * should be the last operation in the whole program because
                     * it adds unnecessary comparisons (at minimum n-checks!).
                     */
                    listOfNewMusic.add(fileEntrySrc);
                } else {
                    sortedListOfSrc.add(fileEntrySrc);
                }
                break;
            default:
                break; // "These are not the files you are looking for."
            }
        }
        // When all is finished and done, save the list of music to a .txt file (will overwrite existing).
        try {
            Paths.get("MLMS_LastSession.txt").toFile().setWritable(true);
            Files.write(Paths.get("MLMS_LastSession.txt"), Arrays.asList(currentSession.toString()), Charset.forName("UTF-8"));
            Paths.get("MLMS_LastSession.txt").toFile().setWritable(false);
        } catch (IOException e) {
            System.err.println("FATAL: Could not save a list of the music to a .txt file!");
        }
        return new DoubleWrapper<List<File>, List<File>>(sortedListOfSrc, listOfNewMusic);
    }
    
    /**
     * This method inspects the list of music given and examines the metadata,
     * updating it if necessary.
     * 
     * @param currentSrcFolder
     *            the current source folder.
     * @param sortedListOfSrc
     *            a list of modified music to be examined.
     * @param listOfNewMusic
     *            a list of new music to be directly copied from src to dst.
     * @throws InterruptedException
     */
    public void updateMetaData(File currentSrcFolder, List<File> sortedListOfSrc, List<File> listOfNewMusic) throws InterruptedException {
        StyleConstants.setForeground(attr, DataClass.INFO_COLOR);
        UI.writeStatusMessage("Updating metadata...", attr);
        
        for (final File fileEntrySorted : sortedListOfSrc) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            final String strFile = fileEntrySorted.getName();
            final int index = strFile.lastIndexOf("."); // If there is no extension, this will default to -1.
            final String strExt = strFile.substring(index + 1);
            try {
                boolean isMP3;
                switch (strExt.toUpperCase()) {
                case "MP3":
                    isMP3 = true;
                case "M4A":
                    // M4A are structurally the same as MP4 files.
                    isMP3 = false;
                    File mpXInDst = new File(dstFolder.getAbsolutePath() + "\\" + fileEntrySorted.getName());
                    updateMusicMetaData(fileEntrySorted, mpXInDst, listOfNewMusic, isMP3);
                    break;
                default:
                    break; // This was not music
                }
            } catch (InvalidAudioFrameException | CannotReadException
                    | IOException | TagException | ReadOnlyFileException
                    | CannotWriteException | ClassCastException e) {
                // TODO Use a logger just like in hotciv/cave
                System.err.println("FATAL: " + e.toString());
            }
        }
    }

    /**
     * Locate the current file in the previous session file.
     * 
     * @param lastSession
     *            our last session file where each entry contains a name and a
     *            last modified.
     * @param lastSessionIndex
     *            the current index describing our location in src.
     * @param strFile
     *            the name of the current file.
     * @return a tuple containing a boolean for whether the file was found and
     *         its last modified date. If it was not found, then the date is 0.
     */
    public DoubleWrapper<Boolean, Long> tryToLocateFileInPreviouSession(
            List<DoubleWrapper<String, Long>> lastSession, int lastSessionIndex,
            String strFile) {
        boolean isOutOfBound = lastSession.size() < lastSessionIndex || lastSessionIndex < 0;
        boolean isPreceedingCurrentFile = false;
        boolean isFollowingCurrentFile = false;
        boolean wasFileLocated = false;
        do {
            int currentFileComparedToPreviousVersion = lastSession.get(lastSessionIndex).getArg1().compareTo(strFile);
            if (currentFileComparedToPreviousVersion < 0) {
                // We are too low behind in our session list.
                if (isFollowingCurrentFile) {
                    // The previous session did not contain the
                    // current file; in the previous iteration, the
                    // index was decremented.
                    break;
                }
                isPreceedingCurrentFile = true;
                lastSessionIndex++;
            } else if (currentFileComparedToPreviousVersion > 0) {
                // We are too far ahead in our session list.
                if (isPreceedingCurrentFile) {
                    // Same conclusion for the opposite reason; previously, the index was incremented. 
                    break;
                }
                isFollowingCurrentFile = true;
                lastSessionIndex--;
            } else {
                // Hoozah! We found the file.
                wasFileLocated = true;
            }
            isOutOfBound = lastSessionIndex >= lastSession.size() || lastSessionIndex < 0;
        } while (!isOutOfBound && !wasFileLocated);
        if (!isOutOfBound) {
            return new DoubleWrapper<Boolean, Long>(wasFileLocated, lastSession.get(lastSessionIndex).getArg2());
        }
        return new DoubleWrapper<Boolean, Long>(wasFileLocated, 0L);
    }
    
    /**
     * Update the appropriate metadata fields if a change is detected. If no
     * change is detected, then overwrite the file (i.e. a fail-safe) as this
     * method should only be called when you know the file in dst has been
     * modified.
     * 
     * @param fileSrc
     *            the file in src.
     * @param fileDst
     *            the file in dst.
     * @param listOfNewMusic
     *            the list of new music to be added directly.
     * @param isMP3
     *            a boolean to determine whether it is an mp3 or m4a/mp4 file.
     * @throws CannotReadException
     * @throws IOException
     * @throws TagException
     * @throws ReadOnlyFileException
     * @throws InvalidAudioFrameException
     * @throws CannotWriteException
     * @throws ClassCastException
     */
    @SuppressWarnings("unchecked")
    public <MusicTag extends Tag> void updateMusicMetaData(File fileSrc,
            File fileDst, List<File> listOfNewMusic, boolean isMP3)
            throws CannotReadException, IOException, TagException,
            ReadOnlyFileException, InvalidAudioFrameException,
            CannotWriteException, ClassCastException {
        // Here we need to make a wrapper class because we need the tag field
        // when updating music.
        List<DoubleWrapper<FieldKey, String>> listOfTagsSrc = new ArrayList<>();
        List<DoubleWrapper<FieldKey, String>> listOfTagsDst = new ArrayList<>();
        // Read metadata from the files.
        MusicTag musicTagSrc;
        AudioFile musicDstWriter;
        MusicTag musicTagDst;
        if (isMP3) {
            musicTagSrc = (MusicTag) ((MP3File) AudioFileIO.read(fileSrc)).getID3v2Tag();
            musicDstWriter = AudioFileIO.read(fileDst);
            musicTagDst = (MusicTag) ((MP3File) musicDstWriter).getID3v2Tag();
        } else {
            musicTagSrc = (MusicTag) AudioFileIO.read(fileSrc).getTag();
            musicDstWriter = AudioFileIO.read(fileDst);
            musicTagDst = (MusicTag) musicDstWriter.getTag();
        }
        /*
         * Before getting every relevant metadata, we check the length of both
         * music files. If the mod. version is not the same, then the music data
         * has been modified. We can do nothing about that so we just replace it
         * and return.
         */
        String musicLengthSrc = musicTagSrc.getFirst(ID3v23Frames.FRAME_ID_V3_LENGTH);
        String musicLengthDst = musicTagDst.getFirst(ID3v23Frames.FRAME_ID_V3_LENGTH);
        if (!musicLengthSrc.equals(musicLengthDst)) {
            listOfNewMusic.add(fileSrc);
            return;
        }
        // Get each relevant tag from src and dst version of the file and save
        // them in lists for later comparisons.
        final ID3v23Frames id3v23Frame = ID3v23Frames.getInstanceOf();
        for (FieldKey fieldKey : listOfFieldKeys) {
            // TODO Ugh, duplication. Can this be done any smarter?
            if (isMP3) {
                /*
                 * getFirst(FieldKey key) does NOT give the right year; it
                 * should be "TYER" and not "TDRC". We get "TYER" by getting it
                 * from the frame ID3v23Frames.FRAME_ID_V3_TYER and this is done
                 * as follows.
                 */
                String fieldName = id3v23Frame.getId3KeyFromGenericKey(fieldKey).getFieldName();
                listOfTagsSrc.add(new DoubleWrapper<>(fieldKey, musicTagSrc.getFirst(fieldName)));
                listOfTagsDst.add(new DoubleWrapper<>(fieldKey, musicTagDst.getFirst(fieldName)));
            } else {
                listOfTagsSrc.add(new DoubleWrapper<>(fieldKey, musicTagSrc.getFirst(fieldKey)));
                listOfTagsDst.add(new DoubleWrapper<>(fieldKey, musicTagDst.getFirst(fieldKey)));
            }
        }
        // Now for the comparisons.
        DoubleWrapper<FieldKey, String> keyTagFile;
        //boolean didIDetectAChange = false;
        for (int i = 0; i < listOfTagsSrc.size(); i++) {
            keyTagFile = listOfTagsDst.get(i);
            if (!listOfTagsSrc.get(i).getArg2().equals(keyTagFile.getArg2())) {
                // Update metadata of the target music file (but do not write it yet!).
                musicTagDst.setField(keyTagFile.getArg1(), listOfTagsSrc.get(i).getArg2());
                //didIDetectAChange = true;
            }
        }
        /*
         * Artworks, however, are a special case. Notice that we are only
         * interested in the first artwork as the others are assumed to be
         * mistakes since they are not shown when the music is played.
         */
        Artwork srcArtwork = musicTagSrc.getFirstArtwork();
        Artwork dstArtwork = musicTagDst.getFirstArtwork();
        if (srcArtwork != null && dstArtwork == null) {
            musicTagDst.setField(srcArtwork);
            //didIDetectAChange = true;
        } else if (srcArtwork == null && dstArtwork != null) {
            musicTagDst.deleteArtworkField();
            //didIDetectAChange = true;
        } else if (srcArtwork != null && dstArtwork != null) {
            // Only get the first artwork.
            byte[] srcArtworkArr = srcArtwork.getBinaryData();
            byte[] dstArtworkArr = dstArtwork.getBinaryData();
            if (!Arrays.equals(srcArtworkArr, dstArtworkArr)) {
                musicTagDst.deleteArtworkField();
                musicTagDst.setField(srcArtwork);
                //didIDetectAChange = true;
            }
        }
        //if (!didIDetectAChange) {
        //    /*
        //     * No metadata change was detected. Our filter might not be
        //     * comprehensive enough (most likely as this filter was made for
        //     * myself). However, we would not end in this method if SOMETHING
        //     * had not changed. So we will just replace the whole file on dst
        //     * instead.
        //     */
        //    listOfNewMusic.add(fileSrc);
        //} else {
        //    // Write the metadata once and for all.
        //    musicDstWriter.setTag(musicTagDst);
        //    musicDstWriter.commit();
        //    UI.updateProgressBar(2);
        //}
        // Write the metadata once and for all.
        musicDstWriter.setTag(musicTagDst);
        musicDstWriter.commit();
        UI.updateProgressBar(2);
    }
    

    /**
     * This method copies new music to {@link #dstFolder}.
     * 
     * @param currentSrcFolder
     *            the current source folder.
     * @param listOfNewMusic
     *            a list of new music to be added directly.
     * @throws InterruptedException
     */
    public void addNewMusicList(File currentSrcFolder, List<File> listOfNewMusic) throws InterruptedException {
        for (final File newMusic : listOfNewMusic) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            String strFile = newMusic.getName();
            //Path targetPath = dstFolder.toPath().resolve(currentSrcFolder.toPath().relativize(currentSrcFolder.toPath()));
            Path targetPath = dstFolder.toPath().resolve(strFile);
            try {
                Files.copy(newMusic.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                StyleConstants.setForeground(attr, DataClass.NEW_MUSIC_COLOR);
                UI.writeStatusMessage("Added " + strFile + ".", attr);
            } catch (IOException e) {
                StyleConstants.setForeground(attr, DataClass.ERROR_COLOR);
                UI.writeStatusMessage("FATAL: Could not copy " + strFile + " to destination." + newMusic.toPath() + " and " + targetPath, attr);
                e.printStackTrace();
            }
            UI.updateProgressBar(2);
        }
    }
    
    /**
     * Delete orphaned music in the specified folder if the option was checked.
     * This should be the first operation of the program. Otherwise, it adds
     * more comparisons and bugs out the sorted list.
     * 
     * @param listOfFolder
     *            a list of files in the specified folder
     * @param pathOfFolder
     *            the path to the folder.
     * @throws InterruptedException 
     */
    public void lookForAndDeleteOrphanedMusicInDst(File[] listOfFolder, String pathOfFolder) throws InterruptedException {
        for (final File fileEntryDst : listOfFolder) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            File fileOnSrc = new File(pathOfFolder + "\\" + fileEntryDst.getName());
            if (fileOnSrc.exists()) {
                continue;
            }
            if (fileEntryDst.delete()) {
                StyleConstants.setForeground(attr, DataClass.DEL_MUSIC_COLOR);
                UI.writeStatusMessage("Deleted " + fileEntryDst.getName(), attr);
                UI.updateProgressBar(1);
            } else {
                StyleConstants.setForeground(attr, DataClass.ERROR_COLOR);
                UI.writeStatusMessage("Could not delete " + fileEntryDst.getName() + ".", attr);
            }
        }
    }
    
    /**
     * Load and return the contents of the previous session file, if available.
     * The music contained in the .txt file should be sorted in an alphabetic
     * order. Use this fact to search through this list and the current list of
     * music (i.e. the src folder) at the same time.
     * 
     * @return a list containing the previous session. Otherwise, it is empty.
     * @throws InterruptedException
     */
    public List<DoubleWrapper<String, Long>> tryToLoadPreviousSession() throws InterruptedException {
        File lastSession = new File("MLMS_LastSession.txt");
        // If the file does not exist, then create it for the future syncing.
        if (!lastSession.exists()) {
            try {
                lastSession.createNewFile();
            } catch (IOException e) {
                StyleConstants.setForeground(attr, DataClass.ERROR_COLOR);
                UI.writeStatusMessage("FATAL: Could not create a file to store the current list of music in!", attr);
            }
        }
        List<DoubleWrapper<String, Long>> lastSessionList = new ArrayList<>();
        // Try-with-ressources to ensure that the stream is closed. Notice that
        // we do not just make a new instance of FileReader because it uses
        // Java's platform default encoding, and that is not always correct!
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(lastSession), Charset.forName("UTF-8")))) {
            // Syntax: name of file on the first line and its last modified date on the next line.
            String line = br.readLine();
            while (line != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                String name = line;
                line = br.readLine();
                // To avoid NumberFormatException on empty lines.
                if (line != null) {
                    long lastMod = Long.parseLong(line);
                    lastSessionList.add(new DoubleWrapper<>(name, lastMod));
                }
                line = br.readLine();
            }
        } catch (FileNotFoundException e) {
            SimpleAttributeSet attr = new SimpleAttributeSet();
            StyleConstants.setForeground(attr, DataClass.INFO_COLOR);
            UI.writeStatusMessage("No last sync session was found.", attr);
        } catch (IOException e) {
            System.err.println("Error when loading last sync session: " + e.getMessage());
        }
        return lastSessionList;
    }
    
    /**
     * Update the current session file with new entries. We use a StringBuilder
     * to pass the reference to the string by value and thus update the session
     * file correctly.
     * 
     * @param currentSession
     *            A StringBuilder representation of the current session file.
     * @param strFile
     *            the current file.
     * @param fileLastMod
     *            the last modified date of the file.
     */
    public void updateCurrentSession(StringBuilder currentSession, String strFile, long fileLastMod) {
        currentSession.append(strFile + "\n");
        currentSession.append(fileLastMod + "\n");
    }
    
    public void setAddNewMusicOption(boolean option) {
        optionAddNewMusic = option;
    }
    
    public void setDeleteOrphanedMusic(boolean option) {
        optionDeleteOrphanedMusic = option;
    }
    
    public void setSearchInSubdirectories(boolean option) {
        optionSearchInSubdirectories = option;
    }
}
