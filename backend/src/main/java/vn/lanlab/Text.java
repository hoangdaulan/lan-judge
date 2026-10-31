package vn.lanlab;

/** Unicode rules retained from the original backend's strings/unicode functions. */
final class Text {
  private Text() {}

  static boolean space(int c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
  }

  static String strip(String s) {
    int start = 0, end = s.length();
    while (start < end && space(s.codePointAt(start)))
      start += Character.charCount(s.codePointAt(start));
    while (end > start && space(s.codePointBefore(end)))
      end -= Character.charCount(s.codePointBefore(end));
    return s.substring(start, end);
  }

  static String upper(String s) {
    var out = new StringBuilder();
    s.codePoints().map(Character::toUpperCase).forEach(out::appendCodePoint);
    return out.toString();
  }
}
