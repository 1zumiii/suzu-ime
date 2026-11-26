#!/system/bin/sh
set -eu
cd "$1"
for entry in ./* ./.[!.]* ./..?*; do
    [ -e "$entry" ] && [ ! -L "$entry" ] || continue
    find "$entry" \( -type d -exec printf 'D%s\0' {} + -o -type f -exec printf 'F%s\0' {} + \)
done
