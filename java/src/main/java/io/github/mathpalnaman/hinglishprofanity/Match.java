package io.github.mathpalnaman.hinglishprofanity;

/**
 * One piece of evidence. Never a verdict.
 *
 * @param term the wordlist entry that matched, in its canonical form
 * @param matchedText the substring of the original input that produced the match
 * @param index start of {@code matchedText} in the original input, in UTF-16
 *     code units (the same unit as {@link String#substring(int, int)})
 * @param endIndex end of {@code matchedText}, exclusive
 * @param severity how offensive the word is, 1 to 4. A property of the word.
 * @param confidence how reliable this match is. A property of the match.
 * @param method which strategy found it
 * @param lang the wordlist the term came from: {@code "en"}, {@code "hi-deva"}
 *     or {@code "hi-latin"} (see {@link Lang#fromJsValue})
 */
public record Match(
    String term,
    String matchedText,
    int index,
    int endIndex,
    int severity,
    Confidence confidence,
    Method method,
    String lang) {}
