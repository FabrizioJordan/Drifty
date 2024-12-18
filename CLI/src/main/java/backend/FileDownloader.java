package backend;

import cli.utils.Utility;
import init.Environment;
import properties.LinkType;
import properties.Program;
import support.DownloadMetrics;
import support.Job;
import utils.MessageBroker;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static cli.support.Constants.*;
import static utils.Utility.sleep;

public class FileDownloader implements Runnable {
    private static final MessageBroker M = Environment.getMessageBroker();
    private final Job job;
    private final DownloadMetrics downloadMetrics;
    private final int numberOfThreads;
    private final long threadMaxDataSize;
    private final String dir;
    private final String link;
    private final Path directoryPath;
    private final LinkType linkType;
    private String fileName;
    private URL url;

    public FileDownloader(Job job) {
        this.job = job;
        this.link = job.getDownloadLink();
        this.linkType = LinkType.getLinkType(link);
        this.fileName = job.getFilename();
        this.dir = job.getDir();
        this.directoryPath = Paths.get(dir);
        this.downloadMetrics = new DownloadMetrics();
        this.numberOfThreads = downloadMetrics.getThreadCount();
        this.threadMaxDataSize = downloadMetrics.getMultiThreadingThreshold();
        downloadMetrics.setMultithreading(false);
    }

    private void downloadFile() {
        try {
            ReadableByteChannel readableByteChannel;
            try {
                boolean supportsMultithreading = downloadMetrics.isMultithreadingEnabled();
                long totalSize = downloadMetrics.getTotalSize();
                if (supportsMultithreading) {
                    List<FileOutputStream> fileOutputStreams = new ArrayList<>(numberOfThreads);
                    List<Long> partSizes = new ArrayList<>(numberOfThreads);
                    List<File> tempFiles = new ArrayList<>(numberOfThreads);
                    List<DownloaderThread> downloaderThreads = new ArrayList<>(numberOfThreads);
                    long partSize = Math.floorDiv(totalSize, numberOfThreads);
                    long start;
                    long end;
                    FileOutputStream fileOut;
                    File file;
                    for (int i = 0; i < numberOfThreads; i++) {
                        file = Files.createTempFile(fileName.hashCode() + String.valueOf(i), ".tmp").toFile();
                        fileOut = new FileOutputStream(file);
                        start = i == 0 ? 0 : ((i * partSize) + 1); // The start of the range of bytes to be downloaded by the thread
                        end = (numberOfThreads - 1) == i ? totalSize : ((i * partSize) + partSize); // The end of the range of bytes to be downloaded by the thread
                        DownloaderThread downloader = new DownloaderThread(url, fileOut, start, end);
                        downloader.start();
                        fileOutputStreams.add(fileOut);
                        partSizes.add(end - start);
                        downloaderThreads.add(downloader);
                        tempFiles.add(file);
                    }
                    ProgressBarThread progressBarThread = new ProgressBarThread(fileOutputStreams, partSizes, fileName, dir, totalSize, downloadMetrics);
                    progressBarThread.start();
                    M.msgDownloadInfo(String.format(DOWNLOADING_F, fileName));
                    // check if all the files are downloaded
                    while (!mergeDownloadedFileParts(fileOutputStreams, partSizes, downloaderThreads, tempFiles)) {
                        sleep(500);
                    }
                    for (File tempFile : tempFiles) {
                        Files.deleteIfExists(tempFile.toPath());
                    }
                } else {
                    InputStream urlStream = url.openStream();
                    readableByteChannel = Channels.newChannel(urlStream);
                    FileOutputStream fos = new FileOutputStream(directoryPath.resolve(fileName).toFile());
                    ProgressBarThread progressBarThread = new ProgressBarThread(fos, totalSize, fileName, dir, downloadMetrics);
                    progressBarThread.start();
                    M.msgDownloadInfo(String.format(DOWNLOADING_F, fileName));
                    fos.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                    fos.close();
                    urlStream.close();
                }
                downloadMetrics.setActive(false);
                // keep the main thread from closing the IO for a short amount of time so the UI thread can finish and give output
                Utility.sleep(1800);
            } catch (SecurityException e) {
                M.msgDownloadError("Write access to the download directory is DENIED! " + e.getMessage());
            } catch (FileNotFoundException fileNotFoundException) {
                M.msgDownloadError(FILE_NOT_FOUND_ERROR);
            } catch (IOException e) {
                M.msgDownloadError(FAILED_TO_DOWNLOAD_CONTENTS + e.getMessage());
            }
        } catch (NullPointerException e) {
            M.msgDownloadError(FAILED_READING_STREAM);
        }
    }

    private void downloadYoutubeOrInstagram(boolean isSpotifySong) {
        String[] fullCommand = new String[]{Program.get(Program.YT_DLP), "--quiet", "--progress", "-P", dir, link, "-o", fileName, "-f", (isSpotifySong ? "bestaudio" : "mp4")};
        ProcessBuilder processBuilder = new ProcessBuilder(fullCommand);
        processBuilder.inheritIO();
        M.msgDownloadInfo(String.format(DOWNLOADING_F, fileName));
        Process process;
        int exitValueOfYtDlp = 1;
        try {
            process = processBuilder.start();
            process.waitFor();
            exitValueOfYtDlp = process.exitValue();
        } catch (IOException e) {
            M.msgDownloadError("Failed to start download process for \"" + fileName + "\"");
        } catch (Exception e) {
            String msg = e.getMessage();
            String[] messageArray = msg.split(",");
            if (messageArray.length >= 1 && messageArray[0].toLowerCase().trim().replaceAll(" ", "").contains("cannotrunprogram")) { // If yt-dlp program is not marked as executable
                M.msgDownloadError(DRIFTY_COMPONENT_NOT_EXECUTABLE_ERROR);
            } else if (messageArray.length >= 1 && "permissiondenied".equals(messageArray[1].toLowerCase().trim().replaceAll(" ", ""))) { // If a private YouTube / Instagram video is asked to be downloaded
                M.msgDownloadError(PERMISSION_DENIED_ERROR);
            } else if ("videounavailable".equals(messageArray[0].toLowerCase().trim().replaceAll(" ", ""))) { // If YouTube / Instagram video is unavailable
                M.msgDownloadError(VIDEO_UNAVAILABLE_ERROR);
            } else {
                M.msgDownloadError("An Unknown Error occurred! " + e.getMessage());
            }
        }
        if (exitValueOfYtDlp == 0) {
            M.msgDownloadInfo(String.format(SUCCESSFULLY_DOWNLOADED_F, fileName));
            if (isSpotifySong) {
                M.msgDownloadInfo("Converting to mp3 ...");
                String conversionProcessMessage = utils.Utility.convertToMp3(directoryPath.resolve(fileName).toAbsolutePath());
                if (conversionProcessMessage.contains("Failed")) {
                    M.msgDownloadError(conversionProcessMessage);
                } else {
                    M.msgDownloadInfo("Successfully converted to mp3!");
                }
            }
        } else if (exitValueOfYtDlp == 1) {
            M.msgDownloadError(String.format(FAILED_TO_DOWNLOAD_F, fileName));
        } else {
            M.msgDownloadError("An Unknown Error occurred! Exit code: " + exitValueOfYtDlp);
        }
    }

    public boolean mergeDownloadedFileParts(List<FileOutputStream> fileOutputStreams, List<Long> partSizes, List<DownloaderThread> downloaderThreads, List<File> tempFiles) throws IOException {
        // check if all files are downloaded
        int completed = 0;
        FileOutputStream fileOutputStream;
        DownloaderThread downloaderThread;
        long partSize;
        for (int i = 0; i < numberOfThreads; i++) {
            fileOutputStream = fileOutputStreams.get(i);
            partSize = partSizes.get(i);
            downloaderThread = downloaderThreads.get(i);
            if (fileOutputStream.getChannel().size() < partSize) {
                if (!downloaderThread.isAlive()) {
                    M.msgDownloadError("Error encountered while downloading the file! Please try again.");
                }
            } else if (!downloaderThread.isAlive()) {
                completed++;
            }
        }
        // check if it is merged-able
        if (completed == numberOfThreads) {
            try (FileOutputStream fos = new FileOutputStream(directoryPath.resolve(fileName).toFile())) {
                long position = 0;
                for (int i = 0; i < numberOfThreads; i++) {
                    File f = tempFiles.get(i);
                    FileInputStream fs = new FileInputStream(f);
                    ReadableByteChannel rbs = Channels.newChannel(fs);
                    fos.getChannel().transferFrom(rbs, position, f.length());
                    position += f.length();
                    fs.close();
                    rbs.close();
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void run() {
        try {
            // If the link is of a YouTube or Instagram video, then the following block of code will execute.
            if (linkType.equals(LinkType.YOUTUBE) || linkType.equals(LinkType.INSTAGRAM)) {
                downloadYoutubeOrInstagram(LinkType.getLinkType(job.getSourceLink()).equals(LinkType.SPOTIFY));
            } else {
                url = new URI(link).toURL();
                URLConnection openConnection = url.openConnection();
                openConnection.connect();
                long totalSize = openConnection.getHeaderFieldLong("Content-Length", -1);
                downloadMetrics.setTotalSize(totalSize);
                String acceptRange = openConnection.getHeaderField("Accept-Ranges");
                downloadMetrics.setMultithreading((totalSize > threadMaxDataSize) && ("bytes".equalsIgnoreCase(acceptRange)));
                if (fileName.isEmpty()) {
                    String[] webPaths = url.getFile().trim().split("/");
                    fileName = webPaths[webPaths.length - 1];
                }
                M.msgDownloadInfo("Trying to download \"" + fileName + "\" ...");
                downloadFile();
            }
        } catch (MalformedURLException | URISyntaxException e) {
            M.msgLinkError(INVALID_LINK);
        } catch (IOException e) {
            M.msgDownloadError(String.format(FAILED_CONNECTION_F, url));
        }
    }
}
