package org.unlaxer.dsl.tools.railroad;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/**
 * Converts SVG content to PNG format using Apache Batik.
 */
public class SvgToPngConverter {

    private SvgToPngConverter() {}

    /**
     * Converts SVG content to PNG and writes to file.
     *
     * @param svgContent  SVG content as string
     * @param pngPath     output path for the PNG file
     * @throws IOException       if I/O fails
     * @throws TranscoderException if SVG transcoding fails
     */
    public static void convertContent(String svgContent, Path pngPath) throws IOException, TranscoderException {
        // Create transcoder
        PNGTranscoder transcoder = new PNGTranscoder();
        // Set DPI to 96 (default screen DPI)
        transcoder.addTranscodingHint(PNGTranscoder.KEY_PIXEL_UNIT_TO_MILLIMETER, 0.264583f);

        // Create input from SVG content string
        TranscoderInput input = new TranscoderInput(new StringReader(svgContent));

        // Create output to PNG file
        try (OutputStream out = new FileOutputStream(pngPath.toFile())) {
            TranscoderOutput output = new TranscoderOutput(out);
            transcoder.transcode(input, output);
        }
    }

    /**
     * Converts an SVG file to PNG.
     *
     * @param svgPath  path to the SVG file
     * @param pngPath  output path for the PNG file
     * @throws IOException       if I/O fails
     * @throws TranscoderException if SVG transcoding fails
     */
    public static void convert(String svgPath, String pngPath) throws IOException, TranscoderException {
        convert(Path.of(svgPath), Path.of(pngPath));
    }

    /**
     * Converts an SVG file to PNG.
     *
     * @param svgPath  path to the SVG file
     * @param pngPath  output path for the PNG file
     * @throws IOException       if I/O fails
     * @throws TranscoderException if SVG transcoding fails
     */
    public static void convert(Path svgPath, Path pngPath) throws IOException, TranscoderException {
        // Read SVG content
        String svgContent = new String(Files.readAllBytes(svgPath), StandardCharsets.UTF_8);
        convertContent(svgContent, pngPath);
    }
}
