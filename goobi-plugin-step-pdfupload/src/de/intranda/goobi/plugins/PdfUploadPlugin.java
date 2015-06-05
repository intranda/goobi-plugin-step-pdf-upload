package de.intranda.goobi.plugins;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.apache.myfaces.custom.fileupload.UploadedFile;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.plugin.interfaces.AbstractStepPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import de.intranda.goobi.plugins.util.PdfFile;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
public class PdfUploadPlugin extends AbstractStepPlugin implements IStepPlugin, IPlugin {

    private static final String PLUGIN_NAME = "intranda_step_pdfUpload";
    private static final Logger logger = Logger.getLogger(PdfUploadPlugin.class);

    private Process process;
    private Fileformat fileformat;
    private String imagefolder;

    private static String ALLOWED_CHARACTER = "[A-Za-z0-9öüäß\\-_.]";

    private UploadedFile uploadedFile = null;

    private List<String> allowedFileExtensions;

    private String comment;
    private PdfFile currentFile;
    private List<PdfFile> uploadedFiles = new ArrayList<PdfFile>();

    private Prefs prefs;
    private DocStructType pageType;
    private MetadataType physType;
    private MetadataType logType;

    private DocStruct logical;
    private DocStruct physical;

    @SuppressWarnings("unchecked")
    public void initialize(Step step, String returnPath) {

        super.returnPath = returnPath;
        super.myStep = step;
        process = myStep.getProzess();

        prefs = process.getRegelsatz().getPreferences();
        pageType = prefs.getDocStrctTypeByName("page");
        physType = prefs.getMetadataTypeByName("physPageNumber");
        logType = prefs.getMetadataTypeByName("logicalPageNumber");

        try {
            fileformat = process.readMetadataFile();
            physical = fileformat.getDigitalDocument().getPhysicalDocStruct();
            logical = fileformat.getDigitalDocument().getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                logical = logical.getAllChildren().get(0);
            }
        } catch (Exception e) {
            logger.error(e);
        }

        XMLConfiguration config = ConfigPlugins.getPluginConfig(this);
        String folder = config.getString("folder", "derivate");
        try {
            if (folder.equalsIgnoreCase("master")) {
                imagefolder = process.getImagesOrigDirectory(false);
            } else if (folder.equalsIgnoreCase("derivate")) {
                imagefolder = process.getImagesTifDirectory(false);
            } else if (folder.equalsIgnoreCase("source")) {
                imagefolder = process.getSourceDirectory() + File.separator;
            } else {
                Helper.setFehlerMeldung("unknownFolderConfigurationError");
                imagefolder = process.getImagesTifDirectory(false);
            }
        } catch (Exception e) {
            logger.error(e);
        }

       File f = new File(imagefolder);
       if (!f.isDirectory()) {
           f.mkdirs();
       }
        
        allowedFileExtensions = config.getList("extensions.extension");
        if (allowedFileExtensions == null || allowedFileExtensions.isEmpty()) {
            allowedFileExtensions = new ArrayList<String>();
            allowedFileExtensions.add("pdf");
        }

        // load old data from mets file/filesystem

        if (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {
            for (DocStruct page : physical.getAllChildren()) {
                String filename = page.getImageName();
                String comment = "";
                if (filename.contains(File.separator)) {
                    filename = filename.substring(filename.lastIndexOf(File.separator) + 1);
                }
                List<? extends Metadata> pageNoMetadata = page.getAllMetadataByType(logType);
                if (pageNoMetadata != null && !pageNoMetadata.isEmpty()) {
                    comment = pageNoMetadata.get(0).getValue();
                    if (comment.equals("uncounted")) {
                        comment = "";
                    }
                }

                File file = new File(imagefolder + filename);
                PdfFile pdf = new PdfFile(filename, comment, file.length());
                uploadedFiles.add(pdf);
            }
        }

    }

    public void uploadFile() {
        ByteArrayInputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            if (this.uploadedFile == null) {
                Helper.setFehlerMeldung("noFileSelected");
                return;
            }

            String basename = this.uploadedFile.getName();
            if (!checkExtension(basename)) {
                Helper.setFehlerMeldung("fileTypeNotAllowed");
                return;
            }
            if (basename.startsWith(".")) {
                basename = basename.substring(1);
            }
            if (basename.contains("/")) {
                basename = basename.substring(basename.lastIndexOf("/") + 1);
            }
            if (basename.contains("\\")) {
                basename = basename.substring(basename.lastIndexOf("\\") + 1);
            }

            basename = basename.replace(" ", "_");

            String replacement = basename.replaceAll(ALLOWED_CHARACTER, "");
            if (!replacement.isEmpty()) {

                Helper.setFehlerMeldung("invalidCharacter");
                return;
            }

            String filename = imagefolder + basename;

            inputStream = new ByteArrayInputStream(this.uploadedFile.getBytes());
            outputStream = new FileOutputStream(filename);

            byte[] buf = new byte[1024];
            int len;
            while ((len = inputStream.read(buf)) > 0) {
                outputStream.write(buf, 0, len);
            }

            File f = new File(filename);

            PdfFile file = new PdfFile(basename, comment, f.length());

            uploadedFiles.add(file);

            try {
                // add to mets file
                DocStruct page = fileformat.getDigitalDocument().createDocStruct(pageType);
                int order = 1;
                if (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {

                    order = physical.getAllChildren().size() + 1;
                }

                Metadata phys = new Metadata(physType);
                phys.setValue("" + order);
                page.addMetadata(phys);

                Metadata log = new Metadata(logType);
                log.setValue(comment);
                page.addMetadata(log);
                // add file name

                page.setImageName(filename);
                physical.addChild(page);

                logical.addReferenceTo(page, "logical_physical");

            } catch (Exception e) {
                logger.error(e);
            }

            try {
                process.writeMetadataFile(fileformat);
            } catch (Exception e) {
                logger.error(e);
            }

        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            Helper.setFehlerMeldung("uploadFailed");
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
            comment = "";
        }
    }

    private boolean checkExtension(String basename) {
        for (String extension : allowedFileExtensions) {
            if (basename.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public UploadedFile getUploadedFile() {
        return this.uploadedFile;
    }

    public void setUploadedFile(UploadedFile uploadedFile) {
        this.uploadedFile = uploadedFile;
    }

    @Override
    public boolean execute() {
        return false;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.PART;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    @Override
    public String getDescription() {
        return PLUGIN_NAME;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public PdfFile getCurrentFile() {
        return currentFile;
    }

    public void setCurrentFile(PdfFile currentFile) {
        this.currentFile = currentFile;
    }

    public void setUploadedFiles(List<PdfFile> uploadedFiles) {
        this.uploadedFiles = uploadedFiles;
    }

    public List<PdfFile> getUploadedFiles() {
        return uploadedFiles;
    }

    public void deleteFile() {
        // remove from list
        if (uploadedFiles.contains(currentFile)) {
            uploadedFiles.remove(currentFile);
        }
        // delete file
        File f = new File(imagefolder + currentFile.getFilename());
        FileUtils.deleteQuietly(f);

        //  remove from mets file

        try {
            for (DocStruct child : physical.getAllChildren()) {
                if (child.getImageName().endsWith(currentFile.getFilename())) {
                    fileformat.getDigitalDocument().getFileSet().removeFile(child.getAllContentFiles().get(0));

                    fileformat.getDigitalDocument().getPhysicalDocStruct().removeChild(child);
                    List<Reference> refs = new ArrayList<Reference>(child.getAllFromReferences());
                    for (ugh.dl.Reference ref : refs) {
                        ref.getSource().removeReferenceTo(child);
                    }
                    break;
                }
            }
            if (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {
                int order = 1;
                for (DocStruct child : physical.getAllChildren()) {
                    // FIX phys order 
                    Metadata pageNo = child.getAllMetadataByType(physType).get(0);
                    pageNo.setValue(String.valueOf(order));
                    order++;
                }
            }
            process.writeMetadataFile(fileformat);
        } catch (Exception e) {
            logger.error(e);
        }
    }

}
