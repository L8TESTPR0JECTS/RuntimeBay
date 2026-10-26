#!/bin/zsh
set -eu
root=${0:A:h:h}
temporary=$(mktemp -d "${TMPDIR:-/tmp}/runtimebay-assist.XXXXXX")
trap 'rm -rf "$temporary"' EXIT

cat > "$temporary/fakecli" <<'CLI'
#!/bin/sh
case " $* " in
  *" -ot "*) printf '%s\n' "[Debug] [Error] Error while parsing flags from args [-ot]: unknown shorthand flag: 'o' in -ot" ':0' ;;
  *) printf '%s\n' ':1' ;;
esac
CLI
chmod +x "$temporary/fakecli"
cat > "$temporary/unregistered" <<'CLI'
#!/bin/sh
printf 'unsafe validator invocation\n' > "$RUNTIMEBAY_TEST_MARKER"
CLI
chmod +x "$temporary/unregistered"
mkdir -p "$temporary/path-fixtures/child"
touch "$temporary/path-fixtures/file.txt"
export RUNTIMEBAY_TEST_ROOT="$root" RUNTIMEBAY_TEST_CLI="$temporary/fakecli" RUNTIMEBAY_TEST_UNREGISTERED="$temporary/unregistered" RUNTIMEBAY_TEST_MARKER="$temporary/unexpected-invocation" RUNTIMEBAY_ASSIST_FILE="$temporary/assist.json" RUNTIMEBAY_TEST_PATH_ROOT="$temporary/path-fixtures"

zsh -f <<'ZSH'
source "$RUNTIMEBAY_TEST_ROOT/backend/src/main/resources/dev/runtimebay/runtimebay-zsh-assist.zsh"
commands[fakecli]="$RUNTIMEBAY_TEST_CLI"
commands[unregistered]="$RUNTIMEBAY_TEST_UNREGISTERED"
_RUNTIMEBAY_ASSIST_COBRA_COMMANDS[fakecli]=1

BUFFER='fakecli exec -it '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 'valid -it cluster was marked invalid'; exit 1; }

BUFFER='fakecli exec -ot '; CURSOR=${#BUFFER}; _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " == *'fg=#ff8e91,bold'* ]] || { print -u2 'invalid -ot option was not highlighted'; exit 1; }

BUFFER='fakecli exec -it '; CURSOR=${#BUFFER}; _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 'corrected option retained a stale error highlight'; exit 1; }

BUFFER='command_typo '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " == *'fg=#ff8e91,bold'* ]] || { print -u2 'unknown command was not highlighted'; exit 1; }

BUFFER='echo "unfinished '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " == *'fg=#ff8e91,bold'* ]] || { print -u2 'unclosed quote was not highlighted'; exit 1; }

BUFFER='echo ) '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " == *'fg=#ff8e91,bold'* ]] || { print -u2 'shell syntax error was not highlighted'; exit 1; }

BUFFER='if true; then '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 'incomplete compound command was incorrectly marked invalid'; exit 1; }

_runtimebay_assist_load_man_options ls || { print -u2 'native ls option synopsis was not loaded'; exit 1; }
for valid_option in a l t; do
  [[ "${_RUNTIMEBAY_ASSIST_MAN_SHORT[ls]}" == *"|$valid_option|"* ]] || { print -u2 "valid ls -$valid_option was absent from metadata"; exit 1; }
done
[[ "${_RUNTIMEBAY_ASSIST_MAN_SHORT[ls]}" != *'|Z|'* ]] || { print -u2 'invalid ls -Z was present in metadata'; exit 1; }
BUFFER='ls '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
grep -q '"options":\[' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'flag suggestion data missing for ls'; exit 1; }
grep -q '"-a"' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'ls -a suggestion missing'; exit 1; }
BUFFER='ls -l'; CURSOR=${#BUFFER}; _runtimebay_assist_redraw
grep -q '"-l"' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'typed ls -l option was not suggested'; exit 1; }
! grep -q '"-Z"' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'invalid ls -Z was suggested'; exit 1; }
BUFFER="cd $RUNTIMEBAY_TEST_PATH_ROOT/"; CURSOR=${#BUFFER}; _runtimebay_assist_redraw
grep -q '"paths":\["child/"\]' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'cd did not suggest a remaining child directory'; exit 1; }
! grep -q 'file.txt' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'cd suggested a file'; exit 1; }
BUFFER="ls $RUNTIMEBAY_TEST_PATH_ROOT/"; CURSOR=${#BUFFER}; _runtimebay_assist_redraw
grep -q '"paths":\["child/","file.txt"\]' "$RUNTIMEBAY_ASSIST_FILE" || { print -u2 'ls suggestions did not contain only the remaining directory and file names'; exit 1; }
BUFFER='ls -Z '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " == *'fg=#ff8e91,bold'* ]] || { print -u2 'invalid native ls option was not highlighted'; exit 1; }
BUFFER='ls -la '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 'valid native ls option cluster was marked invalid'; exit 1; }
BUFFER='ls -- -Z '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 'a path after -- was treated as an option'; exit 1; }

for native_command in touch cp mv rm mkdir chmod chown; do
  _runtimebay_assist_load_man_options "$native_command" || { print -u2 "native $native_command option synopsis was not loaded"; exit 1; }
done
for valid_command in 'touch -c ' 'cp -R ' 'mv -n ' 'rm -i ' 'mkdir -p ' 'chmod -R ' 'chown -R '; do
  BUFFER=$valid_command; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
  [[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 "valid native option was marked invalid: $valid_command"; exit 1; }
done
BUFFER='touch -t -123456789012 '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ " ${region_highlight[*]} " != *'fg=#ff8e91,bold'* ]] || { print -u2 'an option value beginning with a hyphen was misread as an option'; exit 1; }
for invalid_command in 'touch -Z ' 'cp -Z ' 'mv -Z ' 'rm -Z ' 'mkdir -Z ' 'chmod -Z ' 'chown -Z '; do
  BUFFER=$invalid_command; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
  [[ " ${region_highlight[*]} " == *'fg=#ff8e91,bold'* ]] || { print -u2 "invalid native option was not highlighted: $invalid_command"; exit 1; }
done

BUFFER='unregistered -Z '; CURSOR=${#BUFFER}; region_highlight=(); _runtimebay_assist_redraw
[[ ! -e "$RUNTIMEBAY_TEST_MARKER" ]] || { print -u2 'an unregistered command was executed as a validator'; exit 1; }

print 'Terminal assistant validation checks passed.'
ZSH
