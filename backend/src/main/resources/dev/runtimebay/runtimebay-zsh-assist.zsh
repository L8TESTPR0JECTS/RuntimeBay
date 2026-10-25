# Runtime Bay observes ZLE's editable command line; it never reads terminal output
# or captures input while a program (including a password prompt) owns the TTY.
autoload -Uz add-zsh-hook add-zle-hook-widget

function _runtimebay_assist_json_quote() {
  REPLY=$1
  REPLY=${REPLY//\\/\\\\}
  REPLY=${REPLY//\"/\\\"}
  REPLY=${REPLY//$'\n'/\\n}
  REPLY=${REPLY//$'\r'/\\r}
  REPLY=${REPLY//$'\t'/\\t}
  REPLY=\"$REPLY\"
  print -rn -- "$REPLY"
}

function _runtimebay_assist_redraw() {
  local buffer="$BUFFER" cursor="$CURSOR" command="" command_name="" recognized=false token="" glob="" candidate="" json_buffer json_cwd json_command commands_json='[' paths_json='[' payload
  local -a words command_matches path_matches
  words=(${(z)BUFFER})
  command=${words[1]-}
  command_name=${command:t}

  if [[ -n "$command_name" ]]; then
    if (( ${+commands[$command_name]} || ${+builtins[$command_name]} || ${+functions[$command_name]} || ${+aliases[$command_name]} || ${+reswords[$command_name]} )); then
      recognized=true
    fi
    if [[ "$BUFFER" == "$command"* ]]; then
      if [[ $recognized == true ]]; then
        region_highlight+=("0 ${#command} fg=#8fe3c2,bold")
      elif [[ -n "${words[1]-}" ]]; then
        region_highlight+=("0 ${#command} fg=#ffd18a")
      fi
    fi
  fi

  token="${buffer[1,$cursor]}"
  token="${token##*[[:space:]]}"
  if [[ "$buffer" == "$token"* && -n "$token" ]]; then
    local command_length=${#token} name
    for name in ${(k)commands} ${(k)builtins} ${(k)functions}; do
      if [[ ${#name} -ge $command_length && "${name[1,$command_length]}" == "$token" ]]; then
        command_matches+=("$name")
        (( ${#command_matches[@]} >= 20 )) && break
      fi
    done
    command_matches=(${(on)command_matches})
    command_matches=(${command_matches[1,10]})
  fi

  local path_context=false
  case "$command_name" in
    cd|pushd|ls|cat|less|head|tail|touch|mkdir|rmdir|rm|cp|mv|chmod|chown|open|python|python3|node|java|javac|go|grep|find|source|\. ) path_context=true ;;
  esac
  if [[ "$token" == */* || ( "$buffer" != "$token" && $path_context == true ) ]]; then
    if [[ "$token" == /* ]]; then
      glob="$token*"
    elif [[ "$token" == '~/'* ]]; then
      glob="$HOME/${token#\~/}*"
    elif [[ "$token" == ./* || "$token" == ../* ]]; then
      glob="$PWD/$token*"
    else
      glob="$PWD/$token*"
    fi
    path_matches=( ${~glob}(N) )
    local path_count=0
    for candidate in "${path_matches[@]}"; do
      [[ -d "$candidate" ]] && candidate+="/"
      if [[ "$token" != /* && "$token" != '~/'* && "$token" != ./* && "$token" != ../* ]]; then
        candidate="${candidate##*/}"
      elif [[ "$token" == '~/'* ]]; then
        candidate="~/${candidate#$HOME/}"
      elif [[ "$token" != /* ]]; then
        candidate="${candidate#$PWD/}"
      fi
      paths_json+="$(_runtimebay_assist_json_quote "$candidate"),"
      (( ++path_count >= 10 )) && break
    done
  fi

  for candidate in "${command_matches[@]}"; do commands_json+="$(_runtimebay_assist_json_quote "$candidate"),"; done
  [[ $commands_json == *',' ]] && commands_json=${commands_json%,}
  [[ $paths_json == *',' ]] && paths_json=${paths_json%,}
  commands_json+=']'; paths_json+=']'
  _runtimebay_assist_json_quote "$buffer"; json_buffer=$REPLY
  _runtimebay_assist_json_quote "$PWD"; json_cwd=$REPLY
  _runtimebay_assist_json_quote "$command_name"; json_command=$REPLY
  printf -v payload '{"buffer":%s,"cursor":%d,"cwd":%s,"command":%s,"recognized":%s,"commands":%s,"paths":%s}' "$json_buffer" "$cursor" "$json_cwd" "$json_command" "$recognized" "$commands_json" "$paths_json"
  [[ "$payload" == "${_RUNTIMEBAY_ASSIST_LAST-}" ]] && return
  typeset -g _RUNTIMEBAY_ASSIST_LAST="$payload"
  print -rn -- $'\e]777;runtimebay-assist;'"$payload"$'\a'
}

function _runtimebay_assist_install_widget() {
  add-zle-hook-widget line-pre-redraw _runtimebay_assist_redraw 2>/dev/null
  add-zsh-hook -d precmd _runtimebay_assist_install_widget
}

if [[ -o interactive && -o zle ]]; then
  add-zsh-hook precmd _runtimebay_assist_install_widget
fi
