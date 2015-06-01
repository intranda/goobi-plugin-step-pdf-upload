package de.intranda.goobi.plugins.util;

public class PdfFile {



    private String filename;
    private String comment;

    // in byte
    private long size = 0;

    
    public PdfFile(String filename, String comment, long size) {
        this.filename = filename;
        this.comment = comment;
        this.size = size;
    }
    
    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    //

    public String getSizeForGui() {
        String value = "";
        if (size > 1048576) {
            value = size / 1048576 + " MB";
        } else if (size > 1024) {
            value = size / 1024 + " KB";
        } else {
            value = size + " B";
        }
        return value;

    }

}
