package dev.ghostmason.core;

import java.util.function.Consumer;

/**
 * Picks the "notes" strings out of a JSON answer while it is still streaming, so the
 * player sees the AI's design decisions before the build script is finished.
 */
public final class NotesExtractor {
	private final StringBuilder buffer = new StringBuilder();
	private final Consumer<String> onNote;
	private int scan = -1;
	private boolean finished;
	private int ops;

	public NotesExtractor(Consumer<String> onNote) {
		this.onNote = onNote;
	}

	/** Number of ops seen so far (approximate, counts "op" keys). */
	public int opsSoFar() {
		return ops;
	}

	public void accept(String delta) {
		int before = buffer.length();
		buffer.append(delta);
		countOps(Math.max(0, before - 6));
		if (finished) {
			return;
		}
		if (scan < 0) {
			int key = buffer.indexOf("\"notes\"");
			if (key < 0) {
				return;
			}
			int bracket = buffer.indexOf("[", key);
			if (bracket < 0) {
				return;
			}
			scan = bracket + 1;
		}
		while (true) {
			int i = skipSpaceAndCommas(scan);
			if (i >= buffer.length()) {
				return;
			}
			char c = buffer.charAt(i);
			if (c == ']') {
				finished = true;
				return;
			}
			if (c != '"') {
				finished = true; // not a string list; give up quietly
				return;
			}
			int j = i + 1;
			boolean closed = false;
			StringBuilder s = new StringBuilder();
			while (j < buffer.length()) {
				char d = buffer.charAt(j);
				if (d == '\\') {
					if (j + 1 >= buffer.length()) {
						return; // escape split across deltas
					}
					char e = buffer.charAt(j + 1);
					if (e == 'u') {
						if (j + 5 >= buffer.length()) {
							return;
						}
						try {
							s.append((char) Integer.parseInt(buffer.substring(j + 2, j + 6), 16));
						} catch (NumberFormatException ex) {
							s.append('?');
						}
						j += 6;
						continue;
					}
					s.append(switch (e) {
						case 'n' -> ' ';
						case 't' -> ' ';
						default -> e;
					});
					j += 2;
					continue;
				}
				if (d == '"') {
					closed = true;
					break;
				}
				s.append(d);
				j++;
			}
			if (!closed) {
				return; // wait for the rest of this string
			}
			scan = j + 1;
			String note = s.toString().trim();
			if (!note.isEmpty()) {
				onNote.accept(note);
			}
		}
	}

	private int skipSpaceAndCommas(int i) {
		while (i < buffer.length() && (Character.isWhitespace(buffer.charAt(i)) || buffer.charAt(i) == ',')) {
			i++;
		}
		return i;
	}

	private void countOps(int from) {
		int i = from;
		while ((i = buffer.indexOf("\"op\"", i)) >= 0) {
			if (i >= lastOpIndex) {
				ops++;
				lastOpIndex = i + 4;
			}
			i += 4;
		}
	}

	private int lastOpIndex;
}
