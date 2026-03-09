grep -r serialVersionUID * | grep -o "private.*" | sort | uniq -d | sed 's/^.*=//' | sed 's/;//' | sed 's/^ //' | sed 's/^-/\\\\-/' | xargs -I PATTERNS grep -r PATTERNS . | cut -f1 -d ':
