package com.femzyk.klc.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QuestionTextParser — one canonical structured-question text format for
 * every import path (desktop PDF/DOCX/TXT/CSV importer; the web upload page
 * implements the same grammar in JS). Pure and unit-tested.
 *
 * <p>Grammar (blank line ends a question):
 * <pre>
 * Question: What is 2 + 2?          (or "1. What is 2 + 2?" or bare text)
 * Topic: Addition                   (optional)
 * [x] 4                             ([x] = correct, [ ] = wrong)
 * [ ] 22
 * A. 4                              (lettered options also accepted)
 * B. 22
 * Answer: A                         (optional answer key for lettered options)
 * </pre>
 * A question with exactly True/False options is typed TRUE_FALSE, else MCQ.
 * Questions with no options at all are returned with an empty option list so
 * callers can fall back to legacy line-mode handling.
 */
public final class QuestionTextParser {

    private QuestionTextParser() {}

    public record ParsedOption(String label, String text, boolean correct) {}
    public record ParsedQuestion(String text, String topic, String type,
                                 List<ParsedOption> options) {
        public boolean hasOptions() { return options != null && options.size() >= 2; }
        public long correctCount() { return options == null ? 0 : options.stream().filter(ParsedOption::correct).count(); }
    }

    private static final Pattern Q_PREFIX   = Pattern.compile("^(?:Question\\s*:|Q\\s*\\d*\\s*[:.)])\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERING  = Pattern.compile("^\\d{1,4}\\s*[.)]\\s+");
    private static final Pattern TOPIC      = Pattern.compile("^Topic\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKED_OPT = Pattern.compile("^\\[(x| )\\]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LETTER_OPT = Pattern.compile("^([A-E])\\s*[.)]\\s+(.*)$");
    private static final Pattern ANSWER     = Pattern.compile("^Answer\\s*:\\s*([A-E])\\b.*$", Pattern.CASE_INSENSITIVE);

    public static List<ParsedQuestion> parse(String raw) {
        List<ParsedQuestion> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;

        String[] lines = raw.split("\\R");
        String curTopic = "";
        StringBuilder curQ = null;
        List<String[]> curOpts = new ArrayList<>();
        String curAnswer = null;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                if (curQ != null && !curQ.toString().isBlank()) {
                    out.add(build(curQ.toString(), curTopic, curOpts, curAnswer));
                }
                curQ = null; curTopic = ""; curOpts = new ArrayList<>(); curAnswer = null;
                continue;
            }
            Matcher tm = TOPIC.matcher(line);
            if (tm.matches()) { curTopic = tm.group(1).trim(); continue; }
            Matcher am = ANSWER.matcher(line);
            if (am.matches()) { curAnswer = am.group(1).toUpperCase(); continue; }
            Matcher mm = MARKED_OPT.matcher(line);
            if (mm.find()) {
                if (curQ == null) curQ = new StringBuilder();
                curOpts.add(new String[]{mm.group(2).trim(), "x".equalsIgnoreCase(mm.group(1)) ? "1" : "0"});
                continue;
            }
            Matcher lm = LETTER_OPT.matcher(line);
            if (lm.matches() && curQ != null && !curQ.toString().isBlank()) {
                curOpts.add(new String[]{lm.group(2).trim(), "0"});
                continue;
            }
            // Question-start lines: explicit prefix, numbering, or any plain
            // line when no question is open (NEVER silently dropped).
            String stripped = Q_PREFIX.matcher(line).replaceFirst("");
            boolean explicit = !stripped.equals(line);
            String numbered = NUMBERING.matcher(stripped).replaceFirst("");
            if (explicit || curQ == null || (!curOpts.isEmpty() && NUMBERING.matcher(line).find())) {
                if (curQ != null && !curQ.toString().isBlank() && !curOpts.isEmpty()) {
                    out.add(build(curQ.toString(), curTopic, curOpts, curAnswer));
                    curTopic = ""; curOpts = new ArrayList<>(); curAnswer = null;
                    curQ = new StringBuilder(numbered);
                    continue;
                }
                if (curQ == null) { curQ = new StringBuilder(numbered); continue; }
            }
            // continuation of question text or of the last option
            if (!curOpts.isEmpty()) {
                String[] last = curOpts.get(curOpts.size() - 1);
                last[0] = (last[0] + " " + line).trim();
            } else if (curQ != null) {
                curQ.append(curQ.length() > 0 ? " " : "").append(numbered);
            }
        }
        if (curQ != null && !curQ.toString().isBlank())
            out.add(build(curQ.toString(), curTopic, curOpts, curAnswer));
        return out;
    }

    private static ParsedQuestion build(String text, String topic,
                                        List<String[]> rawOpts, String answerKey) {
        List<ParsedOption> opts = new ArrayList<>();
        String[] labels = {"A", "B", "C", "D", "E"};
        for (int i = 0; i < rawOpts.size() && i < 5; i++) {
            String label = labels[i];
            boolean correct = "1".equals(rawOpts.get(i)[1])
                || (answerKey != null && answerKey.equals(label));
            opts.add(new ParsedOption(label, rawOpts.get(i)[0], correct));
        }
        String type = opts.size() == 2
            && opts.stream().anyMatch(o -> o.text().equalsIgnoreCase("true"))
            && opts.stream().anyMatch(o -> o.text().equalsIgnoreCase("false"))
            ? "TRUE_FALSE" : "MCQ";
        return new ParsedQuestion(text.trim(), topic == null ? "" : topic.trim(),
            type, List.copyOf(opts));
    }

    /** True when the text carries structured options (safe to import as-is). */
    public static boolean looksStructured(String raw) {
        return raw != null && (MARKED_OPT.matcher(raw).find()
            || Pattern.compile("(?m)^[A-E]\\s*[.)]\\s+\\S").matcher(raw).find());
    }
}
