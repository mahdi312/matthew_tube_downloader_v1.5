package com.mst.matt.matthew_tube_downloader.service.extractor;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * One row in the Quality Picker dialog — a single downloadable format/stream
 * detected by yt-dlp or by HTML scraping.
 */
public class FormatInfo {

    private final StringProperty formatId   = new SimpleStringProperty("");
    private final StringProperty resolution = new SimpleStringProperty("");
    private final StringProperty fps        = new SimpleStringProperty("");
    private final StringProperty codec      = new SimpleStringProperty("");
    private final StringProperty container  = new SimpleStringProperty("");
    private final StringProperty bitrate    = new SimpleStringProperty("");
    private final StringProperty filesize   = new SimpleStringProperty("");
    private final StringProperty note       = new SimpleStringProperty("");
    private final StringProperty directUrl  = new SimpleStringProperty("");   // optional, scraping path
    private final StringProperty kind       = new SimpleStringProperty("");   // video / audio / muxed / subtitle

    public FormatInfo() {}

    public String getFormatId()         { return formatId.get(); }
    public void setFormatId(String v)   { formatId.set(v); }
    public StringProperty formatIdProperty() { return formatId; }

    public String getResolution()       { return resolution.get(); }
    public void setResolution(String v) { resolution.set(v); }
    public StringProperty resolutionProperty() { return resolution; }

    public String getFps()              { return fps.get(); }
    public void setFps(String v)        { fps.set(v); }
    public StringProperty fpsProperty() { return fps; }

    public String getCodec()            { return codec.get(); }
    public void setCodec(String v)      { codec.set(v); }
    public StringProperty codecProperty() { return codec; }

    public String getContainer()        { return container.get(); }
    public void setContainer(String v)  { container.set(v); }
    public StringProperty containerProperty() { return container; }

    public String getBitrate()          { return bitrate.get(); }
    public void setBitrate(String v)    { bitrate.set(v); }
    public StringProperty bitrateProperty() { return bitrate; }

    public String getFilesize()         { return filesize.get(); }
    public void setFilesize(String v)   { filesize.set(v); }
    public StringProperty filesizeProperty() { return filesize; }

    public String getNote()             { return note.get(); }
    public void setNote(String v)       { note.set(v); }
    public StringProperty noteProperty() { return note; }

    public String getDirectUrl()        { return directUrl.get(); }
    public void setDirectUrl(String v)  { directUrl.set(v); }
    public StringProperty directUrlProperty() { return directUrl; }

    public String getKind()             { return kind.get(); }
    public void setKind(String v)       { kind.set(v); }
    public StringProperty kindProperty() { return kind; }
}
