package io.github.escosis.vivothemehelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small Markdown subset. Only generated inline tags reach Android's styled-text parser. */
final class DocRenderer {
    private static final Pattern HEADING = Pattern.compile("(#{1,2})[ \\t]+(.+)");
    private static final Pattern LIST = Pattern.compile("([-+*]|\\d{1,9}[.)])[ \\t]+(.+)");
    private static final String REQUIRED = "（必看）";
    private static final Pattern INLINE = Pattern.compile(
            "\\\\([\\\\`*_\\[\\]()!])|(?<!`)(`+)(?!`)(.+?)(?<!`)\\2(?!`)"
            + "|(!?)\\[([^\\[\\]]+)\\]\\(([^\\s()]+)\\)"
            + "|(?<!\\*)\\*\\*([^*]+)\\*\\*(?!\\*)|(?<!\\w)__([^_]+)__(?!\\w)"
            + "|(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!\\w)_([^_]+)_(?!\\w)");

    static final class Block {
        final int heading;
        final boolean important;
        final String marker;
        final String html;

        Block(int heading, String marker, String text) {
            this.heading = heading;
            this.important = heading == 2 && text.endsWith(REQUIRED);
            this.marker = marker;
            StringBuilder out = new StringBuilder();
            inline(out, important ? text.substring(0, text.length() - REQUIRED.length()).trim() : text, true);
            this.html = out.toString();
        }
    }

    private DocRenderer() {}

    static List<Block> render(BufferedReader reader) throws IOException {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        boolean firstLine = true;
        for (String raw; (raw = reader.readLine()) != null;) {
            if (firstLine && raw.startsWith("\uFEFF")) raw = raw.substring(1);
            firstLine = false;
            String line = raw.trim();
            Matcher heading = HEADING.matcher(line);
            Matcher list = LIST.matcher(line);
            if (line.isEmpty()) {
                flush(blocks, paragraph);
            } else if (heading.matches()) {
                flush(blocks, paragraph);
                blocks.add(new Block(heading.group(1).length(), null, heading.group(2)));
            } else if (list.matches()) {
                flush(blocks, paragraph);
                String marker = list.group(1);
                blocks.add(new Block(0, marker.length() == 1 ? "•" : marker, list.group(2)));
            } else {
                if (paragraph.length() != 0) paragraph.append(' ');
                paragraph.append(line);
            }
        }
        flush(blocks, paragraph);
        return blocks;
    }

    static String httpsUrl(String raw) {
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getPort() > 65535) return null;
            return "https" + uri.toASCIIString().substring(uri.getScheme().length());
        } catch (URISyntaxException error) {
            return null;
        }
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() != 0) {
            blocks.add(new Block(0, null, paragraph.toString()));
            paragraph.setLength(0);
        }
    }

    private static void inline(StringBuilder out, String text, boolean allowLinks) {
        Matcher match = INLINE.matcher(text);
        int start = 0;
        while (match.find()) {
            escape(out, text.substring(start, match.start()));
            if (match.group(1) != null) {
                escape(out, match.group(1));
            } else if (match.group(3) != null) {
                String code = match.group(3);
                if (code.startsWith(" ") && code.endsWith(" ") && !code.trim().isEmpty())
                    code = code.substring(1, code.length() - 1);
                out.append("<tt>");
                escape(out, code);
                out.append("</tt>");
            } else if (match.group(4) != null) {
                String url = allowLinks && match.group(4).isEmpty() ? httpsUrl(match.group(6)) : null;
                if (url == null) {
                    escape(out, match.group());
                } else {
                    out.append("<a href=\"");
                    escape(out, url);
                    out.append("\">");
                    inline(out, match.group(5), false);
                    out.append("</a>");
                }
            } else if (match.group(7) != null || match.group(8) != null) {
                span(out, "b", match.group(7) != null ? match.group(7) : match.group(8), allowLinks);
            } else {
                span(out, "i", match.group(9) != null ? match.group(9) : match.group(10), allowLinks);
            }
            start = match.end();
        }
        escape(out, text.substring(start));
    }

    private static void span(StringBuilder out, String tag, String text, boolean allowLinks) {
        out.append('<').append(tag).append('>');
        inline(out, text, allowLinks);
        out.append("</").append(tag).append('>');
    }

    private static void escape(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(text.charAt(i));
            }
        }
    }
}
