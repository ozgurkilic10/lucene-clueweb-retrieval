package edu.anadolu.qpp;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Inverse Collection Term Frequency (ICTF)
 */
public class ICTF extends Base {

    public ICTF(Path indexPath) throws IOException {
        super(indexPath, "contents");
    }

    /**
     * Inverse Collection Term Frequency (ictf) of a term
     */
    @Override
    public double value(String word) throws IOException {
        return Math.log((double) sumTotalTermFreq / ctf(field, word));
    }
}
