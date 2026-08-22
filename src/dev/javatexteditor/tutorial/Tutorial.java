package dev.javatexteditor.tutorial;

/**
 * {@code :tutor} コマンドで開く対話型チュートリアルの本文。
 * vimtutor 同様、実際にこのテキストをエディタの操作で編集しながら学ぶ形式。
 * 自動採点は行わない（vimtutor と同じ設計判断。詳細は
 * .claude/skills/editor-tutorial/SKILL.md を参照）。
 */
public final class Tutorial {

    private Tutorial() {}

    public static final String CONTENT = """
==============================================================================
=              Welcome to the JavaTextEditor Tutorial                       =
==============================================================================

This editor combines Vim-style "modal editing" with Java development
support. Like vimtutor (Vim's own tutorial), this tutorial teaches you
by having you actually edit this very text.

How to proceed:
  1. Read each lesson's explanation.
  2. Try the instructions written under "Practice" right here in this
     buffer.
     - This is not "reading material" but "practice exercises". Please
       actually press the keys.
  3. When it works, press j to move down and go to the next lesson.
  4. It's fine if you make a mistake. Press u in NORMAL mode to undo.

To exit the tutorial, type :q and press Enter.
(Nothing is saved. If you want to return to your original file, press
Ctrl+U to go back to the buffer you had open before opening this
tutorial. Ctrl+P moves forward again.)

This tutorial has 19 lessons in total. The first half (1-13) covers
standard Vim operations (including the substitute command, macros, and
case conversion), the middle part (14-18) covers this editor's own
features (including Java/C development support), and the last one (19)
summarizes what to do when you get stuck. Detailed explanations of every
feature are also available under docs/manual/.

Now let's scroll down. Try pressing the j key a few times.



==============================================================================
Lesson 1: The concept of modes
==============================================================================

This editor has several "modes". Right now you are in NORMAL mode (the
mode for "operating" on text: moving, deleting, copying, etc). Check
that the status line at the bottom of the screen shows "-- NORMAL --".

There are four main modes.

  NORMAL  : the mode for "operating" on the document - move, delete,
            copy, etc (the default state)
  INSERT  : the mode for "typing" text (enter with i or a)
  VISUAL  : the mode for "selecting" a range (enter with v or V)
  COMMAND : the mode for running "commands" like :w or :q (enter with :)

From any mode, pressing the Escape key returns you to NORMAL mode. When
in doubt, just press Escape - that's the basic habit of Vim-style
editors.

Practice: Right now, press the Escape key once (staying in NORMAL mode
      is fine). This is practice to build the habit of "when in doubt,
      Escape".



==============================================================================
Lesson 2: Basic cursor movement (h j k l)
==============================================================================

Instead of the arrow keys, you can use four keys that let you move
without leaving the home row.

    k  up
  h left  right  l
    j  down

Practice 1: Place the cursor on the line below and press l four times to
       reach the word "here".
       The cursor is currently here          reach here when done

Practice 2: Press h a few times to move back near the start of the line.

Practice 3: Press j twice to move down two lines, then press k twice to
       return to the original line.

The arrow keys still work as before, but once you're used to hjkl you
can edit without moving your fingers off the home row.



==============================================================================
Lesson 3: Word movement, line start/end, file start/end
==============================================================================

Besides moving one character at a time, there are keys for moving by
larger units.

  w  to the start of the next word
  b  to the start of the previous word
  e  to the end of the current (or next) word
  0  to the "absolute" start of the line (leftmost, including indent)
  ^  to the first "non-blank" character of the line (past the indent)
  $  to the end of the line
  gg to the start of the file
  G  to the end of the file

Practice 1: On the line below, press w three times to move word by word
       from Java to PieceTable.
       This editor uses a Java PieceTable for the buffer.

Practice 2: On the line above, press $ to reach the end, then 0 to
       return to the start.

Practice 3 (a bit risky, so try it once you're comfortable): Jump to the
       start of the file with gg, then to the end of the file with G.
       Finally, use gg again to return to the start of the file and find
       your way back to this line (with many lines, gg/G is faster than
       holding j - this practice lets you feel that).



==============================================================================
Lesson 4: Deleting characters and undo
==============================================================================

  x           delete the character under the cursor
  dd          delete the whole current line
  u           undo the last operation
  Ctrl+Shift+R  redo an undone operation

Practice 1: The line below has extra characters mixed in. Use x to
       remove each X one at a time until it reads "Hello World".
       HXeXllXo WXorXld

Practice 2: If you delete too much, press u. Each press goes back one
       step.

Practice 3: Delete the unneeded line below with dd.
       This line is a dummy line for practicing dd deletion.

Practice 4: Restore the line you deleted in Practice 3 with u. Then
       press Ctrl+Shift+R to go back to the deleted state again (redo).



==============================================================================
Lesson 5: Insert mode (i / a / o)
==============================================================================

You cannot type characters while in NORMAL mode. To type text you need
to enter INSERT mode.

  i  start inserting "before" the cursor
  a  start inserting "after" the cursor
  o  open a new line "below" the current line and start inserting
  Escape  leave INSERT mode and return to NORMAL mode

Practice 1: Move the cursor to the start of the line below, press i,
       type "Hello, ", then press Escape to return to NORMAL mode.
       world.

Practice 2: Move the cursor to the end of the line below (you can use
       $), press a, type " - looks good", then press Escape to return.
       Practice adding text to the end of this line

Practice 3: On the line below, press o to open a new line and enter
       INSERT mode. Type any line you like, then press Escape to return.
       Practice creating a new line below this one



==============================================================================
Lesson 6: Yank (copy) and paste
==============================================================================

In Vim, "copying" is called "yanking".

  yy  yank (copy) the current line
  dd  delete the current line (this also yanks it - i.e. cut)
  p   paste the yanked content "after/below" the cursor
  P   paste the yanked content "before/above" the cursor

Practice 1: On the line below, press yy then p. The same line is
       duplicated.
       Practice yanking and duplicating this line

Practice 2: On the line below, press dd to cut it, move down 1-2 lines,
       then press p to "move" the line to another place.
       Practice moving this line to a different place



==============================================================================
Lesson 7: VISUAL mode (range selection)
==============================================================================

  v  enter character-wise VISUAL mode (extend the selection with hjkl)
  V  enter line-wise VISUAL LINE mode
  y  yank the selection and return to NORMAL mode
  d  delete the selection (also yanks it) and return to NORMAL mode
  Escape  clear the selection and return to NORMAL mode
  v  (when pressed while in VISUAL mode) clear the selection and return
     to NORMAL mode
     Note: v is a toggle - it both enters VISUAL mode and, when pressed
        again while in VISUAL mode, returns to NORMAL mode (same as
        Escape).
  V  (when pressed while in VISUAL LINE mode) clear the selection and
     return to NORMAL mode

While in VISUAL/VISUAL LINE mode, you can also extend the selection with
word movement (w b e), line start/end (0 ^ $), and file start/end
(gg G). You can also indent the selected lines with > and dedent with <
(prefix a number to specify how many levels, e.g. 3> indents 3 levels).

Practice 1: At the start of the line below, press v, then press l
       several times to extend the selection to "select practice", then
       press y to yank it.
       This text is for select practice.

Practice 2: On the line below, press V to select the whole line. Press d
       to delete the whole line.
       A line for practicing deletion in VISUAL LINE mode

Practice 3: On the line below, press V to select the line, then press >
       to indent it. Press < afterward to undo it.
       A line for trying indentation.



==============================================================================
Lesson 8: VISUAL BLOCK mode (rectangular selection)
==============================================================================

This is the rectangular (column-range) selection mode, equivalent to
Vim's Ctrl+V. Use it when you want to edit "just one column" across
multiple lines at once.

  Ctrl+V  enter VISUAL BLOCK mode (from NORMAL mode)
  h j k l extend the rectangle
  y / d   yank / delete the rectangular range
  I       insert text simultaneously on every selected line, at the
          left edge column of the rectangle
  A       insert text simultaneously, one column to the right of the
          rectangle's right edge
  c       delete the rectangular range then start typing at the same
          position as I (replace)
  r       replace every character covered by the rectangle with the next
          key you press
  Ctrl+V / Escape   exit VISUAL BLOCK mode and return to NORMAL mode

If the yanked content is block-shaped, pasting with p / P aligns the
columns line by line (if there aren't enough lines at the paste target,
new lines are added automatically).

Practice 1: Place the cursor on the first column of the 3 lines below,
       press Ctrl+V, then press j twice to select a rectangle spanning
       3 lines, then press r followed by X.
       The first character of each line is replaced with X.
       AAA apple
       BBB orange
       CCC grape

Practice 2: On the same 3 lines, from the first column, select the
       rectangle with Ctrl+V then j j, press I, type "> ", then press
       Escape. "> " is inserted at the start of all 3 lines at once.
       Line 1 dummy text
       Line 2 dummy text
       Line 3 dummy text



==============================================================================
Lesson 9: Text search (Ctrl+S Ctrl+R * # n N)
==============================================================================

  Ctrl+S               start/advance incremental search (forward)
  Ctrl+R               start/advance incremental search (backward)
  *                    search downward for an exact match of the word
                       under the cursor
  #                    search upward for an exact match of the word
                       under the cursor
  n                    jump to the next match in the same direction as
                       the last */#
  N                    jump in the opposite direction of the last */#

Ctrl+S/Ctrl+R are Emacs-style incremental search. With every character
you type, the cursor jumps to the nearest match in real time, and you
can keep pressing Ctrl+S to move through the matches one after another.
Pressing Ctrl+R reverses the search direction. Enter confirms, Escape
cancels back to the position where the search started. Both NORMAL and
INSERT mode support this.

Matched locations are highlighted in yellow.

Practice 1: In NORMAL mode, press Ctrl+S then type tutorial. The cursor
       jumps in real time to where this word appears. Keep pressing
       Ctrl+S to move to the next occurrence, and press Enter to
       confirm.

Practice 2: Place the cursor over the word "sample" on the line below
       and press the * key - it jumps to the next occurrence of the same
       word.
       This is a sample. The word sample appears several times in this
       sample sentence.



==============================================================================
Lesson 10: Command mode (save, quit, open a file, split the screen)
==============================================================================

  :        enter COMMAND mode (a command input field appears at the
           bottom of the screen)
  :w       save (overwrite) the current file
  :w path  save to the given path (this path becomes the "current file"
           afterward)
  :e path  open the given file
  :e       open a new empty buffer (:enew does the same)
  :sp / :split          split the screen left and right
  :vs / :vsplit / :vsp  split the screen top and bottom
  :q       close the current screen (pane). If it's the last one, quit
  :wq      save, then :q

This tutorial itself has no file to save to.
(Trying :w should show a "no file name" error. Feel free to try it -
nothing will break.)

Practice: In NORMAL mode, press : to open the command input field, then
      practice cancelling the command by pressing Escape without typing
      anything.
      (Getting used to this round trip - entering COMMAND mode with :
       and leaving with Escape - will help you smoothly try :grep and
       :rename in later lessons.)



==============================================================================
Lesson 11: The substitute command (:s)
==============================================================================

This is Vim's substitute command. It searches for text using a regular
expression and replaces it with another string.

  :s/pattern/replacement/       substitute only on the current line
  :%s/pattern/replacement/      substitute across the whole buffer
  :10,20s/pattern/replacement/  substitute only on lines 10-20
  :'<,'>s/pattern/replacement/  substitute only in the last VISUAL
                                 selection

You can use a delimiter other than / (e.g. :s#foo#bar#). Appending g at
the end substitutes every match on a line (without it, only the first
match per line is replaced); appending i ignores case. On the
replacement side, & refers to the whole match and \\1-\\9 back-reference
regex groups.

Practice 1: On the line below, type :s/apple/orange/ and press Enter to
       confirm that "apple" is replaced with "orange".
       I bought 2 apples. I'll add one more apple.

Practice 2: The line above should still have a second "apple" left. This
       time type :s/apple/orange/g and use the g flag to replace all of
       them on the line at once.

Practice 3: Press V to select the 3 lines below, press j twice to select
       all 3, then press :. '<,'> is automatically filled into the
       command field; go on and type s/dummy/sample/ and press Enter to
       replace all 3 selected lines at once.
       Line 1 is a dummy
       Line 2 is a dummy
       Line 3 is a dummy



==============================================================================
Lesson 12: Macros (q to record / @ to play)
==============================================================================

This feature lets you record a sequence of key operations and replay it
as many times as you like.

  q{a-z}   start recording into the given register (e.g. qa uses
           register a)
  q        (while recording) stop recording
  @{a-z}   play the macro in the given register once
  @@       replay the register that was last played with @

Keys pressed while recording are actually executed on the spot while
also being recorded. Not just NORMAL mode movement/deletion, but
INSERT-mode typing and mode switches are recorded too.

Practice: Let's turn the operation of adding "- " to the start of the
      3 lines below into a macro. Place the cursor on line 1, start
      recording with qa, then perform I (insert at line start) -> type
      "- " -> Escape -> j (move to next line), and finally press q to
      stop recording the macro. Then press @a twice to apply the same
      operation to the remaining 2 lines.
      apple
      orange
      grape



==============================================================================
Lesson 13: Case conversion (~ / guu / gUU / g~~)
==============================================================================

This feature toggles the case of the character under the cursor or of a
whole line.

  ~       toggle the case of the character under the cursor and move
          right
  guu     lowercase the entire current line
  gUU     uppercase the entire current line
  g~~     toggle the case of the entire current line

In VISUAL / VISUAL LINE / VISUAL BLOCK mode, you can use u (lowercase) /
U (uppercase) / ~ (toggle) on the selected range.

Practice 1: Press ~ a few times over the first character of the line
       below and confirm the cursor moves right each time the case
       toggles.
       hello world

Practice 2: On the line below, typing guu lowercases the whole line, and
       gUU uppercases the whole line. Try both.
       MixedCASE Line

Practice 3: On the line below, press V to select the line, then press U
       to uppercase the whole selected line.
       lowercase line to convert



==============================================================================
Lesson 14: This editor's own features (1) - movement & efficiency keys
==============================================================================

From here on are keybindings unique to this editor, not found in
standard Vim.

Even in INSERT mode, you can move the cursor with Emacs-style keys
without pressing Escape (saving you the trouble of switching modes back
and forth).

  Ctrl+F / Ctrl+B   move right/left by 1 character (in INSERT mode)
  Ctrl+N / Ctrl+P   move down/up by 1 line (in INSERT mode)
  Ctrl+A / Ctrl+E   move to line start (non-blank)/end (in INSERT mode)
  Alt+F / Alt+B     move to the next/previous word (in INSERT mode)

In NORMAL mode, Space is used as a leader key.

  Space h   to the first non-blank character of the line (same as ^)
  Space l   to the end of the line (same as $)
  Space k   to the start of the file (same as gg)
  Space j   to the end of the file (same as G)

NORMAL mode also has dedicated keys for screen- and line-wise scrolling.

  Ctrl+F / Ctrl+B   scroll down/up by 1 screen
  Ctrl+D            scroll down by half a screen
  Ctrl+E / Ctrl+Y   scroll down/up by 1 line
  H / M / L         jump to the top/middle/bottom line on screen

Jumping to matching brackets, swapping lines, and moving between split
panes are also handy.

  %       jump to the closing bracket matching the ( [ { under the
          cursor (or vice versa; supports nesting, but only works when
          the cursor is "on" a bracket)
  Alt+J   swap the current line with the next line
  Alt+K   swap the current line with the previous line
  sv / ss           split the pane vertically/horizontally (same as
                     :sp / :vs)
  sh sk / sl sj     move focus to the previous/next pane

Practice 1: On the line below, press i to enter INSERT mode, move to the
       end of the line with Ctrl+E, then back to the start with Ctrl+A.
       Finally press Escape to leave.
       A line for trying Emacs-style cursor movement.

Practice 2: Place the cursor on either of the 2 lines below and press
       Alt+J or Alt+K to confirm their order swaps.
       Line 1 (starts on top)
       Line 2 (starts on bottom)

Practice 3: Place the cursor over the "(" on the line below and press %.
       It jumps to the matching ")". Press % again to return to "(".
       Example call to sampleFunction(arg1, arg2, arg3)



==============================================================================
Lesson 15: This editor's own features (2) - Java editing support
==============================================================================

This editor also serves as an "editor for writing Java". These features
are especially useful while a .java file is open.

  K           the "universal jump/reference key" that looks up the
              identifier under the cursor. If a declaration (class,
              method, field) is found in the project it jumps there;
              otherwise it looks the symbol up as a JDK class and shows
              a summary in the status bar (with a description too, if
              Javadoc is installed). Over a native method in the form
              ClassName.method it also looks up the JNI implementation
  Shift+J     go back one step to where the last K jump started from
  gr          search project-wide for references to the identifier under
              the cursor (g followed by r; results are listed in a
              dedicated buffer, and Enter jumps to the chosen location)
  Ctrl+Space  open the code completion popup (in INSERT mode; identifiers
              in the working directory and JDK class names become
              candidates, and the candidates auto-update as you type)
  Alt+/       open a word-only completion popup (does not include JDK
              class names)
  [g / [d     jump to the next/previous line with a compile error or
              warning
  F2          show a dialog listing the errors/warnings on the current
              line (a global key usable from any mode)
  Space g g   auto-generate a getter from the field declaration on the
              current line
  Space g s   likewise, auto-generate a setter
  Space g d   generate both getter and setter
  Space i o     remove all unused import statements at once (same as the
              :oi command)
  Ctrl+C, Ctrl+O  insert @Override plus a newline at the cursor (Ctrl+C
              followed by Ctrl+O)
  :main java / :main javac   jump to the actual entry point of the java /
              javac command (the launcher's source code)
  F10         compile the entire Java project under the working directory
              (the result is shown in the *compile* buffer)
  F11         find and run public static void main in the project (if
              more than one is found, you can choose from a list;
              standard output is shown together in the *run* buffer)
  F12         run F10, and if it succeeds, continue on to run the
              equivalent of F11

F10/F11/F12 switch behavior based on the type of the currently open
file. If a .java file is open they act as Java (roughly javac); if a .c
file is open they act as C (roughly gcc). See Lesson 16 for details.

auto-import (automatic import completion) is also included. While
editing a .java file, when you leave INSERT mode and return to NORMAL
mode (by pressing Escape), compile errors are analyzed and import
statements for any unresolved class names are inserted automatically.
When there are multiple candidates, they are shown numbered in the
status bar and you can choose one with a number key.

Practice: Since this tutorial is a plain text file, K, gr, and
      auto-import won't have their real effect here, but if you're
      comfortable, open a .java file (you can open another file with :e)
      and try out this section's features.



==============================================================================
Lesson 16: This editor's own features (3) - C language development support
==============================================================================

This editor supports C language development in addition to Java. These
features are active while a .c / .h file is open. Where the Java side is
implemented with the standard JDK API (javac), the C side calls out to
an external C compiler (auto-detected in the order gcc -> clang -> cc)
to achieve the same thing. In environments where no C compiler is found,
these features are silently disabled (the editor itself still works
fine).

  F10         compile all .c files under the working directory into a
              single executable (gcc -Wall -o bin/a.out ...). The result
              is shown in the same *compile* buffer as Java
  F11         run the compiled executable (bin/a.out). Standard
              output/error are shown in the *run* buffer in real time,
              and lines from standard error are shown in red
  F12         run F10, and if it succeeds, continue on to run F11
  [g / [d     jump to the next/previous line with a compile error or
              warning. Analyzed with gcc -fsyntax-only on save and on
              returning from INSERT to NORMAL, showing E/W gutter marks
              and squiggly underlines (shared with Java)
  F2          show a dialog listing the errors/warnings on the current
              line (shared)
  K           jump to definition (Shift+K). Behavior depends on cursor
              position:
              - over a function name -> jumps to the implementation in
                the project (the .c function body). Even if only a
                declaration exists in a header, it can trace the
                implementation
              - over a macro/type (struct/enum/typedef) -> to the
                definition line (usually in a header)
              - on a #include "foo.h" / <foo.h> line -> opens that
                header file
              - over a standard-library identifier like printf / NULL /
                size_t -> if not found in the project, queries the
                actually installed C compiler and jumps to the standard
                header (supports both Windows and Linux)
  Shift+J     go back to where the last K jump started from (shared with
              Java)

Automatic #include insertion (the C version of Java's auto-import) is
also included. If you use a standard-library symbol like printf /
malloc / strlen / sqrt / size_t without #including the corresponding
header, the needed header (e.g. <stdio.h>) is inserted automatically
when you return from INSERT to NORMAL mode. Pressing Space i o (or :oi)
scans the whole source and adds any missing headers all at once.

Completion (Ctrl+Space / Alt+/), search (:grep / \\g), rename
(:rename), and modal editing were already language-agnostic, and work
the same way in .c / .h files.

  Note on multiple programs: F10 links all .c files into a single
  executable. If several independent programs, each with their own
  main(), live in the same directory, you'll get a "multiple definition
  of main" link error (this feature is mainly designed for a single
  program made of multiple files).

Practice: Since this tutorial is plain text it can't actually be
      compiled, but if you're comfortable, open a .c file with :e
      hello.c, write a short program that uses printf, and press F12.

  Where bin/ ends up: compiled output is placed as a "sibling of the src
  folder", the same as Java (the binDirFor convention). In a layout
  without a src folder, it goes to bin/ right under the working
  directory.



==============================================================================
Lesson 17: This editor's own features (4) - project-wide features
==============================================================================

Beyond single-file operations, there are also features that work across
the entire working directory (project).

  :grep <pattern>          full-text search across the working directory
                            with a regular expression. Results are listed
                            in a dedicated buffer, and Enter jumps to the
                            matching file/line
  :rename <old> <new>      rename a symbol project-wide, everywhere at
                            once
  \\f                       file name search (FILESEARCH mode)
  \\g                       grep search of file contents (FILESEARCH
                            mode)
  Space f                  telescope-style fuzzy file search (same
                            listing as \\f/\\g)
  Space /                  telescope-style live grep
  Space b                  telescope-style buffer list
  :cd <path>                change the working directory (the base
                            directory for grep/rename/search/telescope
                            all switch together)
  :pwd                      check the current working directory

:grep / \\f / \\g / gr (Lesson 15) also have a "bang (!)" full-file-search
variant. Normally directories like .git, build, and node_modules are
skipped, but adding ! makes it a full scan including those.

  :grep! <pattern>   the bang version of :grep
  \\f!pattern          the bang version of \\f (prefix with ! and press
                       Enter)
  \\g!pattern          the bang version of \\g
  gR                  the bang version of gr (g followed by Shift+R)

Moving to a directory with :cd automatically opens FILER mode (the file
browser).

  Ctrl+N / Ctrl+P   move down/up through the list
  Enter             enter a directory, or open a file
  /                 further narrow down the list with a search
  Esc               exit FILER mode

Pressing Tab while typing :cd or :e in COMMAND mode completes the path
name (if there are multiple candidates, you can choose from a list).

Practice: In NORMAL mode, type :pwd and press Enter, and confirm the
      current working directory is shown in the status bar.



==============================================================================
Lesson 18: This editor's own features (5) - appearance and other settings
==============================================================================

There are also fine-grained keys for adjusting the screen's appearance.

  Ctrl+Shift+Left / Right   shrink/enlarge the font cell width
  Ctrl+Shift+Up / Down      shrink/enlarge the font cell height

The half-width font and color theme can be switched with commands.

  :font 0    use Misc Fixed as the half-width font (default)
  :font 1    use IBM Plex Mono as the half-width font
  :font 2    use JetBrains Mono as the half-width font
  :font 3    use Comic Mono as the half-width font
  :color 0   use the dark mono color theme (default)
  :color 1   use the beige mono color theme
  :color 2   use the dark color theme
  :color 3   use the light color theme

In addition to the current mode, the status line (at the very bottom of
the screen) shows, on the right, a clock, CPU/GPU usage, memory usage
(e.g. "CPU 12% | MEM 62%"), and a walking-character animation (shown
only for the active screen). Items that can't be read (e.g. no GPU
present) are simply omitted. If there are compile errors, the number of
errors/warnings is also shown here.

While a Markdown file (.md / .markdown) is open, the :view command
switches to a reading view that strips heading/list/quote markup
symbols for easier reading. :mark returns to the original source and
cursor position. The reading view is treated as read-only, so :w will
error with no save destination (to save, return to source view with
:mark first).

Practice: Press Ctrl+Shift+Right once to enlarge the font a little, then
      Ctrl+Shift+Left once to restore it. If you get the chance to open
      a Markdown file, try :view and :mark too.



==============================================================================
Lesson 19: If you get stuck
==============================================================================

  u            undo an operation (works almost anywhere)
  Escape       return to NORMAL mode from any mode
  Ctrl+U       go back one step in the buffer history in NORMAL mode
                (lets you return to the file you had open before this
                tutorial)
  Ctrl+P       the opposite direction of Ctrl+U (move forward in history)
  :q           quit (in this tutorial, this just closes without saving)

If you're not sure what happened, try Escape then u first.



==============================================================================
Great work!
==============================================================================

You've now experienced a full tour of the features. You can always
reopen this tutorial with the :tutor command.

Next steps:
  - Check out docs/manual/ (especially
    10-keybindings-reference.md) for finer-grained keys not covered
    here (like word deletion with Ctrl+W, Tab pair skipping, and more).
  - Open an actual .java file with :e and try the K key and auto-import.
  - Repeating this tutorial a few times until it feels natural is also
    a good idea.

To close this buffer, type :q and press Enter.
""";
}
