// Lists everything clickable on screen, and taps the first match for an argument.
//   hands run find-buttons.js Send
const nodes = scrobot.screen();
const clickable = nodes.filter(n => n.clickable && (n.text || n.desc));
scrobot.log('clickable nodes: ' + clickable.length);
for (const n of clickable) {
  scrobot.log(n.center.join(',') + '  ' + (n.text || n.desc) + '  [' + n.window + ']');
}

const wanted = scrobot.args[0];
if (wanted) {
  const hit = clickable.find(n => ((n.text || '') + ' ' + (n.desc || '')).toLowerCase().includes(wanted.toLowerCase()));
  if (!hit) throw new Error('nothing on screen matches ' + wanted);
  scrobot.log('tapping ' + (hit.text || hit.desc));
  scrobot.click(wanted);
}
