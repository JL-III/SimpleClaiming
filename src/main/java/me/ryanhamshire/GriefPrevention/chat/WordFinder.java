package me.ryanhamshire.GriefPrevention.chat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WordFinder {
    private Pattern pattern;

    public WordFinder(List<String> wordsToFind) {
        if (wordsToFind.size() == 0) return;

        StringBuilder patternBuilder = new StringBuilder();
        for (String word : wordsToFind) {
            if (!word.isEmpty() && !word.trim().isEmpty()) {
                patternBuilder.append("|(([^\\w]|^)").append(Pattern.quote(word)).append("([^\\w]|$))");
            }
        }

        String patternString = patternBuilder.toString();
        if (patternString.length() > 1) {
            //trim extraneous leading pipe (|)
            patternString = patternString.substring(1);
        }
        // No words are defined, match nothing.
        else return;

        this.pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public boolean hasMatch(String input) {
        if (this.pattern == null) return false;
        Matcher matcher = this.pattern.matcher(input);
        return matcher.find();
    }
}
