# Saves a screenshot and the node list, then prints where they went.
# Android's shell is mksh, not bash, and it has no curl: the `scrobot` function
# talks to the app directly.

stamp=$(date +%Y%m%d-%H%M%S)
scrobot shot "screen-$stamp.png" || exit 1
scrobot screen > "$SCROBOT_SHOTS/screen-$stamp.txt"

lines=$(wc -l < "$SCROBOT_SHOTS/screen-$stamp.txt")
scrobot log "saved $lines nodes"
echo "wrote $SCROBOT_SHOTS/screen-$stamp.png"
echo "wrote $SCROBOT_SHOTS/screen-$stamp.txt"
