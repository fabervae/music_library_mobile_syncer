package main;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.MouseInfo;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import data.DataClass;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.stage.DirectoryChooser;
import jmtp.DeviceAlreadyOpenedException;
import jmtp.PortableDevice;
import util.MTPUtil;

@FunctionalInterface
interface dialogMethod {
    public String browseDialog() throws InterruptedException, ExecutionException;
}

public class UI extends JFrame {
    /**
     * Serial user ID
     */
    private static final long serialVersionUID = 8597395032893667211L;
    private JTextField txtSrcDir;
    private JTextField txtDstDir;
    private static JScrollPane centerPanel;
    private static JTextPane statusText;
    private static StyledDocument statusTextDoc;
    private static Semaphore readProgressSemaphore;
    private static int progressBarValue;
    private MusicSyncer musicSyncer;
    private DirectoryChooser dirChooser;
    private static JProgressBar progressBar;
    private final String srcFolderAttr;
    private final int srcFolderIndex;
    private final String dstFolderAttr;
    private final int dstFolderIndex;
    private final String addNewMusicAttr;
    private final int addNewMusicIndex;
    private final String deleteOrphanedAttr;
    private final int deleteOrphanedIndex;
    private final String searchInSubdirectoriesAttr;
    private final int searchInSubdirectoriesIndex;
    private final String srcIsAnMTPDeviceAttr;
    private final int srcIsAnMTPDeviceIndex;
    private final String dstIsAnMTPDeviceAttr; 
    private final int dstIsAnMTPDeviceIndex;
    private final String windowXAttr;
    private int windowX = -1;
    private final String windowYAttr;
    private int windowY = -1;
    private final String windowWidthAttr;
    private int windowWidth = -1;
    private final String windowHeightAttr;
    private int windowHeight = -1;
    private static Robot robotKeepPCAwake;
    private static boolean shouldRobotStop = false;

    /**
     * Launch the application.
     */
    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                UI frame = new UI();
                frame.setVisible(true);
            }
        });
    }

    /**
     * Create the frame.
     */
    public UI() {
        final boolean IS_FCFS = true;
        // Avoid race conditions between MusicSyncer and UI accessing progressBarValue.
        readProgressSemaphore = new Semaphore(-1, IS_FCFS);
        // For reading the settings correctly
        char currIndex = 0;
        srcFolderAttr = "srcFolder=";
        srcFolderIndex = currIndex;
        dstFolderAttr = "dstFolder=";
        dstFolderIndex = ++currIndex;
        addNewMusicAttr = "addNewMusic=";
        addNewMusicIndex = ++currIndex;
        deleteOrphanedAttr= "deleteOrphaned=";
        deleteOrphanedIndex = ++currIndex;
        searchInSubdirectoriesAttr = "searchInSubdirectories=";
        searchInSubdirectoriesIndex = ++currIndex;
        srcIsAnMTPDeviceAttr = "srcIsAnMTPDevice=";
        srcIsAnMTPDeviceIndex = ++currIndex;
        dstIsAnMTPDeviceAttr = "dstIsAnMTPDevice=";
        dstIsAnMTPDeviceIndex = ++currIndex;
		// The following attributes are not represented on the UI and therefore do not
		// need indices like the others above.
        windowXAttr = "windowX=";
        windowYAttr = "windowY=";
        windowWidthAttr = "windowWidth=";
        windowHeightAttr = "windowHeight=";
        // Set up the UI
        setTitle("Music Library Mobile Syncer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // Close application
        // Load MLMS_Settings.txt if available.
        String[] arrayOfSettings = tryToLoadPreviousSettings();
        if (windowX > -1) {
            setBounds(windowX, windowY, windowWidth, windowHeight);
        } else { // Default values
            setBounds(100, 100, 450, 300);
        }
        // Start adding components to the UI
        JPanel topPanel = new JPanel();
        getContentPane().add(topPanel, BorderLayout.NORTH);
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.X_AXIS));
        
        JPanel dirPanel = new JPanel();
        topPanel.add(dirPanel);
        dirPanel.setLayout(new BoxLayout(dirPanel, BoxLayout.Y_AXIS));
        
        centerPanel = new JScrollPane();
        getContentPane().add(centerPanel, BorderLayout.CENTER);
        // Make a text area with customizable text. The document reflects the changes.
        statusTextDoc = new DefaultStyledDocument();
        statusText = new JTextPane(statusTextDoc);
        statusText.setEditable(false);
        statusText.setText("Status window:" + System.lineSeparator());
        centerPanel.setViewportView(statusText);
        
        txtSrcDir = new JTextField();
        dirPanel.add(txtSrcDir);
        txtDstDir = new JTextField();
        dirPanel.add(txtDstDir);
        if (!arrayOfSettings[srcFolderIndex].equals("")) {
            txtSrcDir.setText(arrayOfSettings[srcFolderIndex]);
            txtDstDir.setText(arrayOfSettings[dstFolderIndex]);
        } else {
            txtSrcDir.setText("Input the location of your music library?");
            txtDstDir.setText("Input the location of where to sync it");
        }
        txtSrcDir.setColumns(10);
        txtDstDir.setColumns(10);
        // Initialize west panel with check boxes
        JPanel westPanel = new JPanel();
        getContentPane().add(westPanel, BorderLayout.WEST);
        westPanel.setLayout(new BoxLayout(westPanel, BoxLayout.Y_AXIS));
        // Add check boxes to the panel
        JCheckBox addNewMusicChkBox = new JCheckBox("Add new music");
        // Load previous settings and only care about whether "true" was written correctly.
        addNewMusicChkBox.setSelected(Boolean.valueOf(arrayOfSettings[addNewMusicIndex]));
        addNewMusicChkBox.setForeground(DataClass.NEW_MUSIC_COLOR);
        westPanel.add(addNewMusicChkBox);
        JCheckBox deleteOrphanedChkBox = new JCheckBox("Delete orphaned music");
        deleteOrphanedChkBox.setSelected(Boolean.valueOf(arrayOfSettings[deleteOrphanedIndex]));
        deleteOrphanedChkBox.setForeground(DataClass.DEL_MUSIC_COLOR);
        westPanel.add(deleteOrphanedChkBox);
        JCheckBox searchInSubdirsChkBox = new JCheckBox("Search in subdirectories");
        searchInSubdirsChkBox.setSelected(Boolean.valueOf(arrayOfSettings[searchInSubdirectoriesIndex]));
        westPanel.add(searchInSubdirsChkBox);
        JCheckBox srcIsAnMTPDeviceChkBox = new JCheckBox("Source is an MTP device");
        srcIsAnMTPDeviceChkBox.setSelected(Boolean.valueOf(arrayOfSettings[srcIsAnMTPDeviceIndex]));
        westPanel.add(srcIsAnMTPDeviceChkBox);
        JCheckBox dstIsAnMTPDeviceChkBox = new JCheckBox("Destination is an MTP device");
        dstIsAnMTPDeviceChkBox.setSelected(Boolean.valueOf(arrayOfSettings[dstIsAnMTPDeviceIndex]));
        westPanel.add(dstIsAnMTPDeviceChkBox);
        
        // Initialize browse panel
        JPanel browsePanel = new JPanel();
        topPanel.add(browsePanel);
        browsePanel.setLayout(new BoxLayout(browsePanel, BoxLayout.Y_AXIS));
        /*
        // "Prettify" the FileChooser dialog.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException
                | UnsupportedLookAndFeelException e1) {
            System.err.println("FATAL: Could not open the browse dialog. +
                Please input the destination of source folder manually.");
        } */
        new JFXPanel(); // Initialize JavaFX thread when using its FileChooser (ideally called once)
        dirChooser = new DirectoryChooser();
        // Implement source browse button and its popup menu options
        JButton srcBrowseButton = new JButton("Browse...");
        browsePanel.add(srcBrowseButton);
        
        JPopupMenu popupMenuSrc = new JPopupMenu();
        addPopup(srcBrowseButton, popupMenuSrc);
        
        JMenuItem mntmComputerSrc = new JMenuItem("Computer");
        popupMenuSrc.add(mntmComputerSrc);
        addBrowseDialog(mntmComputerSrc, txtSrcDir, this::browsePCDialog); // Java 8 lambda expression for passing a method!
        
        JMenuItem mntmMtpDeviceSrc = new JMenuItem("MTP Device");
        popupMenuSrc.add(mntmMtpDeviceSrc);
        addBrowseDialog(mntmMtpDeviceSrc, txtSrcDir, this::browseMTPDialog);
        
        // Implement target browse button and its popup menu options
        JButton dstBrowseButton = new JButton("Browse...");
        browsePanel.add(dstBrowseButton);
        
        JPopupMenu popupMenuDst = new JPopupMenu();
        addPopup(dstBrowseButton, popupMenuDst);
        
        JMenuItem mntmComputerDst = new JMenuItem("Computer");
        popupMenuDst.add(mntmComputerDst);
        addBrowseDialog(mntmComputerDst, txtDstDir, this::browsePCDialog);
        
        JMenuItem mntmMtpDeviceDst = new JMenuItem("MTP Device");
        popupMenuDst.add(mntmMtpDeviceDst);
        addBrowseDialog(mntmMtpDeviceDst, txtDstDir, this::browseMTPDialog);
        
        JPanel bottomPanel = new JPanel();
        getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        JButton startButton = new JButton("Start!");
        startButton.addActionListener(new ActionListener() {
            private Thread musicSyncerThread;
            private Thread progressBarThread;

            @Override
            public void actionPerformed(ActionEvent arg0) {
                // Stop execution if the button was pressed while it was running.
                if (startButton.getText().equals("Stop!")) {
					// If the application is interrupted while mouseMove() is called on the robot,
					// then the InterruptedException WILL be caught, letting the programming
					// continue instead of stopping!
                	shouldRobotStop = true;
                    musicSyncerThread.interrupt();
                    progressBarThread.interrupt();
                } else {
                    // Adding stopwatch for easier visualization of algorithm effectiveness.
                    long timeStart = System.currentTimeMillis();
                    // Change the functionality of the button so that it stops
                    // execution when pressed again.
                    startButton.setText("Stop!");
                    // Clear text
                    statusText.setText("");
                    // It does not make sense to be able to change the dirs.
                    srcBrowseButton.setEnabled(false);
                    dstBrowseButton.setEnabled(false);
					// Start the progress bar. The if-clause is a safety measure in case MusicSyncer
					// counted wrong.
                    if (progressBarThread != null && progressBarThread.isAlive()) {
                        progressBarThread.interrupt();
                    }
                    progressBarValue = 0;
                    startProgressBarThread();
                    /*
                     * When the start button is pressed, we make a thread of the
                     * main program. The reasons are two-fold: 1) To ensure the
                     * user can still interact with the application (this
                     * includes aborting the operation or closing the program)
                     * 2) Show progression with status messages and the progress
                     * bar.
                     */
                    Runnable musicSyncerRunnable = new Runnable() {
                        @Override
                        public void run() {
							musicSyncer = new MusicSyncer(txtSrcDir.getText(), txtDstDir.getText(),
									srcIsAnMTPDeviceChkBox.isSelected(), dstIsAnMTPDeviceChkBox.isSelected());
                            // Include the state of the check boxes.
                            musicSyncer.setAddNewMusicOption(addNewMusicChkBox.isSelected());
                            musicSyncer.setDeleteOrphanedMusic(deleteOrphanedChkBox.isSelected());
                            musicSyncer.setSearchInSubdirectories(searchInSubdirsChkBox.isSelected());
                            try {
                                musicSyncer.initiate();
                                progressBarValue = progressBar.getMaximum(); // Set to max in case MusicSyncer counted wrong
                            } catch (InterruptedException e) {
                                SimpleAttributeSet attr = new SimpleAttributeSet();
                                StyleConstants.setForeground(attr, DataClass.INFO_COLOR);
                                writeStatusMsg("Execution was stopped.", attr);
                            } finally {
                                // Restore everything to its default value.
                                startButton.setText("Start!");
                                srcBrowseButton.setEnabled(true);
                                dstBrowseButton.setEnabled(true);
                            }
                            SimpleAttributeSet attr = new SimpleAttributeSet();
                            StyleConstants.setForeground(attr, DataClass.INFO_COLOR);
                            writeStatusMsg("Finished. Time taken: " + 
                                    (System.currentTimeMillis() - timeStart) + " ms.", attr);
                        }
                    };
                    // Tell the robot that it can resume its movements
                    shouldRobotStop = false;
                    musicSyncerThread = new Thread(musicSyncerRunnable);
                    musicSyncerThread.start();
                }
            }

            private void startProgressBarThread() {
                Runnable progressBarRunnable = new Runnable() {
                    @Override
                    public void run() {
                        while (progressBarValue < progressBar.getMaximum()) {
                            try {
                                readProgressSemaphore.acquire();
                                progressBar.setValue(progressBarValue);
                            } catch (InterruptedException ignore) {
                            	return; // Exit the method instead of setting progressBar to max. value.
                            }
                        }
                        // The syncing is done; set the bar to 100 %.
                        progressBar.setValue(progressBar.getMaximum());
                    }
                };
                progressBarThread = new Thread(progressBarRunnable);
                progressBarThread.start();
            }
        });
        bottomPanel.add(startButton);
        bottomPanel.add(progressBar);
        
        // Save settings before exiting the application.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
            	final List<String> settings = Arrays.asList(srcFolderAttr
                        + txtSrcDir.getText() + System.lineSeparator() + dstFolderAttr
                        + txtDstDir.getText() + System.lineSeparator() + addNewMusicAttr
                        + addNewMusicChkBox.isSelected() + System.lineSeparator()
                        + deleteOrphanedAttr
                        + deleteOrphanedChkBox.isSelected() + System.lineSeparator()
                        + searchInSubdirectoriesAttr
                        + searchInSubdirsChkBox.isSelected() + System.lineSeparator()
                        + srcIsAnMTPDeviceAttr
                        + srcIsAnMTPDeviceChkBox.isSelected() + System.lineSeparator()
                        + dstIsAnMTPDeviceAttr
                        + dstIsAnMTPDeviceChkBox.isSelected() + System.lineSeparator()
                        + windowXAttr + getX() + System.lineSeparator()
                        + windowYAttr + getY() + System.lineSeparator()
                        + windowWidthAttr + getWidth() + System.lineSeparator()
                        + windowHeightAttr + getHeight() + System.lineSeparator()
        		);
                try {
                    Files.write(Paths.get("MLMS_Settings.txt"), settings, Charset.forName("UTF-8"));
                } catch (IOException e) {
                    System.err.println("ERROR: Could not save a list of the music to a .txt file!");
                }
            }
        }));
        // Create robot for keeping the pc awake when syncing.
        try {
            robotKeepPCAwake = new Robot();
            robotKeepPCAwake.setAutoDelay(0);
        } catch (AWTException e) {
            System.err.println("FATAL: Could not create a robot for keeping the computer awake "
                    + "while syncing. Your platform does not allow low-level input control.");
        }
    }

	/**
     * Note that we do not want to make the MusicSyncer instance wait! Just
     * release immediately and continue!
     * 
     * @param increment
     *            - the value to increment the progress bar's value by.
	 * @throws InterruptedException 
     */
    public static void updateProgressBar(final int increment) throws InterruptedException {
    	// Move mouse to same location, effectively keeping the PC awake.
    	robotKeepPCAwake.mouseMove(MouseInfo.getPointerInfo().getLocation().x,
    			MouseInfo.getPointerInfo().getLocation().y);
        readProgressSemaphore.release();
        progressBarValue += increment;
        // Check if robot is supposed to stop
        if (shouldRobotStop) {
        	throw new InterruptedException("Robot: Stop button was pressed.");
        }
    }
    
    public static void setMaximumLimitOnProgressBar(final int max) {
        progressBar.setMaximum(max);
    }
    
    private String[] tryToLoadPreviousSettings() {
        String srcFolder = "";
        String dstFolder = "";
        String addNewMusic = "";
        String deleteOrphaned = "";
        String searchInSubdirectories = "";
        String srcIsAnMTPDevice = "";
        String dstIsAnMTPDevice = "";
        final String[] returnArray;
        File settings = new File("MLMS_Settings.txt");
        if (!settings.exists()) {
            returnArray = new String[]{"", ""};
        } else {
        	// Try-with-ressources to ensure that the stream is closed. Notice that
            // we do not just make a new instance of FileReader because it uses
            // Java's platform default encoding, and that is not always correct!
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(settings), Charset.forName("UTF-8")))) {
                String line = br.readLine();
                while (line != null) {
                    if (line.startsWith(srcFolderAttr)) {
                        srcFolder = line.substring(srcFolderAttr.length());
                    } else if (line.startsWith(dstFolderAttr)) {
                        dstFolder = line.substring(dstFolderAttr.length());
                    } else if (line.startsWith(addNewMusicAttr)) {
                        addNewMusic = line.substring(addNewMusicAttr.length());
                    } else if (line.startsWith(deleteOrphanedAttr)) {
                        deleteOrphaned = line.substring(deleteOrphanedAttr.length());
                    } else if (line.startsWith(searchInSubdirectoriesAttr)) {
                        searchInSubdirectories = line.substring(searchInSubdirectoriesAttr.length());
                    } else if (line.startsWith(srcIsAnMTPDeviceAttr)) {
                    	srcIsAnMTPDevice = line.substring(srcIsAnMTPDeviceAttr.length());
                    } else if (line.startsWith(dstIsAnMTPDeviceAttr)) {
                    	dstIsAnMTPDevice = line.substring(dstIsAnMTPDeviceAttr.length());
                    } else if (line.startsWith(windowXAttr)) {
                        windowX = Integer.parseInt(line.substring(windowXAttr.length()));
                    } else if (line.startsWith(windowYAttr)) {
                        windowY = Integer.parseInt(line.substring(windowYAttr.length()));
                    } else if (line.startsWith(windowWidthAttr)) {
                        windowWidth = Integer.parseInt(line.substring(windowWidthAttr.length()));
                    } else if (line.startsWith(windowHeightAttr)) {
                        windowHeight = Integer.parseInt(line.substring(windowHeightAttr.length()));
                    }
                    line = br.readLine();
                }
            } catch (FileNotFoundException e) {
                writeStatusMsg("No previous settings were found.", DataClass.INFO_COLOR);
            } catch (IOException e) {
                System.err.println("Error when loading settings: " + e.getMessage());
            }
			returnArray = new String[] { srcFolder, dstFolder, addNewMusic, deleteOrphaned, searchInSubdirectories,
					srcIsAnMTPDevice, dstIsAnMTPDevice };
        }
        return returnArray;
    }

	/**
	 * Write messages to the UI for the user to see. Only package visible to avoid
	 * calls from, for instance, the test package.
	 * 
	 * @param message
	 *            - the message
	 * @param attributeSet
	 *            - an attribute set for formatting the message
	 */
    static void writeStatusMsg(final String message, final MutableAttributeSet attributeSet) {
        try {
            statusTextDoc.insertString(statusTextDoc.getLength(), message + System.lineSeparator(), attributeSet);
        } catch (BadLocationException e) { // This should not happen
            System.err.println("FATAL: Could not write status message because of an "
                    + "invalid position. The error message: " + e.getMessage());
        }
        // Implement auto-scroll unless the scroll bar is manually moved up.
        if (isViewAtBottom()) {
            scrollToBottom();
        }
    }
    
	/**
	 * Write messages to the UI for the user to see. Only package visible to avoid
	 * calls from, for instance, the test package
	 * 
	 * @param message
	 *            - the message
	 * @param color
	 *            - color of the message
	 * @see main.UI#writeStatusMsg(String, MutableAttributeSet)
	 */
    static void writeStatusMsg(final String message, final Color color) {
    	SimpleAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setForeground(attr, color);
        writeStatusMsg(message, attr);
    }
    
    /**
     * Helper method to determine if the scroll bar is at the bottom (i.e.
     * cannot be scrolled more down).
     * 
     * @return true if the scroll bar is at the bottom; otherwise false.
     */
    private static boolean isViewAtBottom() {
        JScrollBar scrollBar = centerPanel.getVerticalScrollBar();
        final int min = scrollBar.getValue() + scrollBar.getVisibleAmount();
        final int max = scrollBar.getMaximum();
        return min == max;
    }

    private static void scrollToBottom() { 
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                centerPanel.getVerticalScrollBar().setValue(
                    centerPanel.getVerticalScrollBar().getMaximum());
            }
        });
    }
    
	/**
	 * For documentation, please refer to the method specified in @see
	 * 
	 * @see main.UIForMTPFileSystem#showDialog()
	 * @return the path to the folder chosen by the user, if any. Otherwise, it will
	 *         be the empty string.
	 */
    private String browseMTPDialog() {
    	PortableDevice[] devices = MTPUtil.getDevices();
    	if (devices.length == 0) {
    		SimpleAttributeSet attr = new SimpleAttributeSet();
            StyleConstants.setForeground(attr, DataClass.ERROR_COLOR);
            writeStatusMsg("No MTP devices were detected!", attr);
    		return "";
    	}
    	// Now iterating the list of MTP devices.
    	// TODO For now, it is only the first device. Do generalize...
    	PortableDevice mtpDevice = devices[0];
    	try {
    		mtpDevice.open();
    	} catch (DeviceAlreadyOpenedException e) { // Do nothing
    		System.err.println(getClass().getName() + ": Device already open!");
    	}
    	UIForMTPFileSystem uiMTP = new UIForMTPFileSystem(mtpDevice, this); // Initiate MTP folder dialog
		String strFolder = uiMTP.showDialog(); // Wait till a folder has been chosen.
		mtpDevice.close(); // TODO Again, close ALL devices if every one of them is shown
		return strFolder;
    }
    
    /**
	 * Displays a DirectoryChooser dialog where the user can select a folder.
	 * 
	 * @return the path to the folder chosen by the user, if any. Otherwise, it will
	 *         be the empty string.
	 */
    private String browsePCDialog() throws InterruptedException, ExecutionException {
        /*
         * TODO Javadoc for this awesome code is needed. Source: http://stackoverflow.com/a/13804542
         */
        final FutureTask<String> queryFolder = new FutureTask<String>(new Callable<String>() {
            @Override
            public String call() {
                // Show open directory dialog
                final File folder = dirChooser.showDialog(null);
                if (folder != null) {
                    dirChooser.setInitialDirectory(folder);
                    return folder.getAbsolutePath();
                }
                return "";
            }
        });
        Platform.runLater(queryFolder);
        // The following call is synchronous; it will block until queryFolder is done.
        return queryFolder.get();
}
    
	private void addBrowseDialog(JMenuItem component, JTextField txtField, dialogMethod dialog) {
		component.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String srcFolderString = "";
				try {
					srcFolderString = dialog.browseDialog();
				} catch (InterruptedException | ExecutionException ex) {
					System.err.println("FATAL: " + ex.getMessage());
				} finally {
					// If the user did not choose a folder, then keep the current folder.
					if (!srcFolderString.equals("")) {
						txtField.setText(srcFolderString);
					}
				} // Finally end
            } // actionPerformed() end
        }); // Listener end
	}

	/**
	 * Add a pop-up menu to a component on left-click.
	 * 
	 * @param component
	 *            - a component (such as a JButton)
	 * @param popup
	 *            - the JPopupmenu
	 */
	private static void addPopup(Component component, final JPopupMenu popup) {
		component.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseReleased(MouseEvent e) {
				if (!e.isPopupTrigger()) {
					showMenu(e);
				}
			}
			private void showMenu(MouseEvent e) {
				popup.show(e.getComponent(), e.getX(), e.getY());
			}
		});
	}
}
