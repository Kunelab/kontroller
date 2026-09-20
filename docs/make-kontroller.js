/* Regenerates kontroller.svg, the animated README figure: a phone driving a
   screen it is not wired to.

     node docs/make-kontroller.js

   No dependencies, and nothing to install. The figure is animated with CSS
   keyframes rather than script, because GitHub strips script from an SVG but
   does run its CSS. Every moment of the run is a percentage of one loop, so
   the timings live in T below in milliseconds and are converted here rather
   than being typed into the stylesheet by hand. */
const fs = require('fs');

const TOTAL = 9000;
const pc = (ms) => +(ms / TOTAL * 100).toFixed(3);

/* The run, in ms. Everything below is expressed against these. */
const T = {
  fingerDown: 600,
  dragFrom: 900,
  dragTo: 1900,
  tap: 2100,
  tapEnd: 2560,
  fingerUp: 2600,
  keysUp: 2800,
  keysThere: 3150,
  typeFrom: 3300,
  typeStep: 200,
  enter: 4800,
  hitsFrom: 5000,
  hitsThere: 5300,
  hold: 7800,          // everything starts going away here
  away: 8300,
  cursorBack: 8100
};

const QUERY = 'kunelab';
const TYPE_TO = T.typeFrom + QUERY.length * T.typeStep;   // 4700

/* ---- geometry ---- */
const W = 900, H = 340;

const PH = { x: 20, y: 30, w: 200, h: 280 };              // phone
const PAD = { x: 32, y: 60, w: 176, h: 232 };             // its surface
const finger = {
  from: { x: PAD.x + 0.62 * PAD.w, y: PAD.y + 0.72 * PAD.h },
  to: { x: PAD.x + 0.30 * PAD.w, y: PAD.y + 0.26 * PAD.h }
};
const KB = { x: PAD.x, y: 188, w: PAD.w, h: 104 };

const SC = { x: 290, y: 30, w: 590, h: 280 };             // the far screen
const BAR = { x: 312, y: 80, w: 546, h: 36 };             // its search field
const TEXT = { x: 350, y: 104, size: 16 };
const ADV = TEXT.size * 0.6;                              // monospace advance
/* The pointer parks clear of the text it is about to make appear. */
const cursor = { from: { x: 700, y: 236 }, to: { x: 476, y: 98 } };

/* ---- keyboard rows ---- */
const rows = [
  { keys: 'qwertyuiop'.split(''), w: 14.55 },
  { keys: 'asdfghjkl'.split(''), w: 14.55 },
  { keys: ['⇧', 'z', 'x', 'c', 'v', 'b', 'n', 'm', '⌫'], w: 14.55, wide: [0, 8], wideW: 20 },
  { keys: [' ', '↵'], w: 100, wideW: 40, enter: true }
];
const KEY_H = 21, GAP = 2.5, KB_PAD = 4;

function rowGeometry(row) {
  const widths = row.enter
    ? [100, 40]
    : row.keys.map((k, i) => (row.wide && row.wide.indexOf(i) !== -1 ? row.wideW : row.w));
  const total = widths.reduce((a, b) => a + b, 0) + GAP * (widths.length - 1);
  let x = KB.x + KB_PAD + (KB.w - 2 * KB_PAD - total) / 2;
  return widths.map(function (w, i) {
    const at = x;
    x += w + GAP;
    return { x: at, w: w, label: row.keys[i] };
  });
}

/* Which keys light up, and when. */
const flashAt = {};
QUERY.split('').forEach(function (ch, i) { flashAt[ch] = T.typeFrom + i * T.typeStep; });
flashAt['↵'] = T.enter;

let keyMarkup = '';
rows.forEach(function (row, r) {
  const y = 195 + r * (KEY_H + 3);
  rowGeometry(row).forEach(function (k) {
    const lit = flashAt[k.label];
    const cls = lit ? ' class="key lit"' : ' class="key"';
    const delay = lit ? ' style="animation-delay:' + (lit / 1000).toFixed(2) + 's"' : '';
    keyMarkup += '    <rect' + cls + delay + ' x="' + k.x.toFixed(2) + '" y="' + y +
      '" width="' + k.w.toFixed(2) + '" height="' + KEY_H + '" rx="3"/>\n';
    if (k.label && k.label !== ' ') {
      const tcls = lit ? ' class="cap lit"' : ' class="cap"';
      keyMarkup += '    <text' + tcls + delay + ' x="' + (k.x + k.w / 2).toFixed(2) +
        '" y="' + (y + KEY_H / 2 + 3.2) + '" text-anchor="middle">' + k.label + '</text>\n';
    }
  });
});

/* ---- the four things the radio says it is sending ---- */
const reports = [
  { text: 'MOVE', from: T.dragFrom, to: T.tap },
  { text: 'CLICK', from: T.tap, to: T.fingerUp },
  { text: 'KEYS', from: T.typeFrom, to: T.enter },
  { text: 'ENTER', from: T.enter, to: T.hitsFrom }
];
const reportMarkup = reports.map(function (r, i) {
  return '  <text class="report r' + i + '" x="255" y="204" text-anchor="middle">' + r.text + '</text>';
}).join('\n');
const reportCss = reports.map(function (r, i) {
  return '    .r' + i + '{animation-name:say' + i + '}\n' +
    '    @keyframes say' + i + '{\n' +
    '      0%,' + pc(r.from - 1) + '%{opacity:0}\n' +
    '      ' + pc(r.from) + '%,' + pc(r.to - 1) + '%{opacity:1}\n' +
    '      ' + pc(r.to) + '%,100%{opacity:0}\n' +
    '    }';
}).join('\n');

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}"
     role="img" aria-label="A phone acting as a Bluetooth keyboard and mouse: a finger drags on its trackpad, the pointer on a separate screen moves to a search field and clicks it, the phone shows a keyboard, and the word kunelab is typed into that field, which then shows results.">
  <title>One phone, one screen, no cable: trackpad moves the pointer, keyboard types</title>

  <!-- A ${TOTAL / 1000}s loop. Nothing is scripted: GitHub strips script from an SVG
       but runs its CSS, so every moment below is a keyframe percentage of
       those ${TOTAL / 1000} seconds.

         ${T.fingerDown}ms  a finger lands on the pad
         ${T.dragFrom}-${T.dragTo}ms  it drags, and the pointer follows it
         ${T.tap}ms  tap: the field takes focus
         ${T.keysUp}ms  the pad becomes a keyboard
         ${T.typeFrom}-${TYPE_TO}ms  ${QUERY} is typed, one key at a time
         ${T.enter}ms  enter, then the results arrive
         ${T.hold}ms  everything goes back and it starts again        -->
  <style>
    /* An SVG loaded as an image resolves prefers-color-scheme against the
       reader's system, not against the GitHub theme they chose, and the two
       can disagree. So the greys are translucent and sit on either
       background; only ink and paper follow the scheme. */
    :root{
      --ink:#1F2328; --paper:#FFFFFF;
      --muted:#8B949E; --accent:#F47521;
      --line:rgba(128,128,128,.45); --surf:rgba(128,128,128,.10); --key:rgba(128,128,128,.18);
    }
    @media (prefers-color-scheme:dark){ :root{--ink:#E6EDF3; --paper:#0D1117} }

    .shell{fill:var(--surf); stroke:var(--line)}
    .sunk{fill:none; stroke:var(--line)}
    .grip{fill:var(--line)}
    .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
    .tiny{fill:var(--muted); font-size:9px; letter-spacing:1.4px}
    .dot{fill:var(--line)}

    /* --- the finger, and the pointer it is pushing --- */
    .finger{
      fill:var(--accent); fill-opacity:.22; stroke:var(--accent);
      animation:finger ${TOTAL / 1000}s linear infinite;
    }
    @keyframes finger{
      0%,${pc(T.fingerDown - 1)}%{opacity:0; transform:translate(0,0)}
      ${pc(T.fingerDown)}%{opacity:1; transform:translate(0,0)}
      ${pc(T.dragFrom)}%{opacity:1; transform:translate(0,0); animation-timing-function:ease-in-out}
      ${pc(T.dragTo)}%,${pc(T.fingerUp - 1)}%{opacity:1; transform:translate(${(finger.to.x - finger.from.x).toFixed(1)}px,${(finger.to.y - finger.from.y).toFixed(1)}px)}
      ${pc(T.fingerUp)}%,100%{opacity:0; transform:translate(${(finger.to.x - finger.from.x).toFixed(1)}px,${(finger.to.y - finger.from.y).toFixed(1)}px)}
    }

    .ripple{
      fill:none; stroke:var(--accent); transform-box:fill-box; transform-origin:center;
      animation:ripple ${TOTAL / 1000}s linear infinite;
    }
    @keyframes ripple{
      0%,${pc(T.tap - 1)}%{opacity:0; transform:scale(.55)}
      ${pc(T.tap)}%{opacity:.9; transform:scale(.55)}
      ${pc(T.tapEnd)}%,100%{opacity:0; transform:scale(2.4)}
    }

    .pointer{fill:var(--ink); stroke:var(--paper); stroke-width:1; stroke-linejoin:round;
      animation:pointer ${TOTAL / 1000}s linear infinite}
    @keyframes pointer{
      0%,${pc(T.dragFrom)}%{transform:translate(0,0); animation-timing-function:ease-in-out}
      ${pc(T.dragTo)}%,${pc(T.cursorBack)}%{transform:translate(${(cursor.to.x - cursor.from.x).toFixed(1)}px,${(cursor.to.y - cursor.from.y).toFixed(1)}px); animation-timing-function:ease-in-out}
      ${pc(T.away)}%,100%{transform:translate(0,0)}
    }

    /* --- pad and keyboard share the phone: the pad dims, it does not leave --- */
    .padface{animation:dim ${TOTAL / 1000}s linear infinite}
    @keyframes dim{
      0%,${pc(T.keysUp)}%{opacity:1}
      ${pc(T.keysThere)}%,${pc(T.hold)}%{opacity:.3}
      ${pc(T.away)}%,100%{opacity:1}
    }

    .keyboard{animation:slide ${TOTAL / 1000}s linear infinite}
    @keyframes slide{
      0%,${pc(T.keysUp)}%{transform:translateY(${KB.h + 6}px)}
      ${pc(T.keysThere)}%,${pc(T.hold)}%{transform:translateY(0)}
      ${pc(T.away)}%,100%{transform:translateY(${KB.h + 6}px)}
    }

    .key{fill:var(--key); stroke:var(--line); stroke-width:.5}
    .cap{fill:var(--muted); font-size:9px; font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
    /* A lit key is one of the ones this run happens to press; its delay says
       which, so all of them can share one 120ms flash. */
    rect.lit{animation:hitKey ${TOTAL / 1000}s step-end infinite}
    text.lit{animation:hitCap ${TOTAL / 1000}s step-end infinite}
    @keyframes hitKey{0%{fill:var(--accent)} 1.33%,100%{fill:var(--key)}}
    @keyframes hitCap{0%{fill:var(--paper)} 1.33%,100%{fill:var(--muted)}}

    /* --- the radio --- */
    .chip{fill:none; stroke:var(--line)}
    .bt{fill:var(--muted); font-size:9px; letter-spacing:1px}
    .flow{fill:var(--line); animation:flow 1.5s linear infinite}
    @keyframes flow{0%,100%{fill:var(--line)} 40%{fill:var(--accent)}}
    .report{fill:var(--accent); font-size:9px; letter-spacing:1.4px; opacity:0;
      font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
      animation-duration:${TOTAL / 1000}s; animation-iteration-count:infinite;
      animation-timing-function:step-end}
${reportCss}

    /* --- the field being typed into --- */
    .ring{fill:none; stroke:var(--accent); animation:ring ${TOTAL / 1000}s step-end infinite}
    @keyframes ring{
      0%,${pc(T.tap - 1)}%{opacity:0}
      ${pc(T.tap)}%,${pc(T.hold - 1)}%{opacity:1}
      ${pc(T.hold)}%,100%{opacity:0}
    }

    /* The query is one string behind a clip that widens one character at a
       time, so the letters cannot drift out of step with the caret. */
    .reveal{transform-box:fill-box; transform-origin:left center;
      animation:reveal ${TOTAL / 1000}s linear infinite}
    @keyframes reveal{
      0%,${pc(T.typeFrom - 1)}%{transform:scaleX(0)}
      ${pc(T.typeFrom)}%{transform:scaleX(0); animation-timing-function:steps(${QUERY.length},end)}
      ${pc(TYPE_TO)}%,${pc(T.hold - 1)}%{transform:scaleX(1)}
      ${pc(T.hold)}%,100%{transform:scaleX(0)}
    }
    .query{fill:var(--ink); font-size:${TEXT.size}px}

    .caret{fill:var(--accent);
      animation:caretMove ${TOTAL / 1000}s linear infinite, caretBlink .9s step-end infinite}
    @keyframes caretMove{
      0%,${pc(T.tap - 1)}%{visibility:hidden; transform:translateX(0)}
      ${pc(T.tap)}%,${pc(T.typeFrom)}%{visibility:visible; transform:translateX(0);
        animation-timing-function:steps(${QUERY.length},end)}
      ${pc(TYPE_TO)}%,${pc(T.hold - 1)}%{visibility:visible; transform:translateX(${(QUERY.length * ADV).toFixed(1)}px)}
      ${pc(T.hold)}%,100%{visibility:hidden; transform:translateX(0)}
    }
    @keyframes caretBlink{0%{opacity:1} 50%{opacity:0} 100%{opacity:1}}

    .hit{fill:var(--line); animation:hits ${TOTAL / 1000}s linear infinite}
    @keyframes hits{
      0%,${pc(T.hitsFrom)}%{opacity:0; transform:translateY(4px)}
      ${pc(T.hitsThere)}%,${pc(T.hold)}%{opacity:1; transform:translateY(0)}
      ${pc(T.away)}%,100%{opacity:0; transform:translateY(4px)}
    }
  </style>

  <clipPath id="typed">
    <rect class="reveal" x="${TEXT.x}" y="${TEXT.y - TEXT.size}" width="${(QUERY.length * ADV).toFixed(1)}" height="${TEXT.size + 6}"/>
  </clipPath>

  <!-- ============ the phone ============ -->
  <rect class="shell" x="${PH.x}" y="${PH.y}" width="${PH.w}" height="${PH.h}" rx="16"/>
  <rect class="grip" x="${PH.x + 70}" y="${PH.y + 14}" width="60" height="5" rx="2.5"/>
  <rect class="grip" x="${PH.x + 65}" y="${PH.y + PH.h - 12}" width="70" height="4" rx="2"/>

  <g class="padface">
    <rect class="sunk" x="${PAD.x}" y="${PAD.y}" width="${PAD.w}" height="${PAD.h}" rx="5"/>
    <text class="mono tiny" x="${PAD.x + 10}" y="${PAD.y + 18}">TRACKPAD</text>
  </g>

  <circle class="ripple" cx="${finger.to.x.toFixed(1)}" cy="${finger.to.y.toFixed(1)}" r="15"/>
  <circle class="finger" cx="${finger.from.x.toFixed(1)}" cy="${finger.from.y.toFixed(1)}" r="15"/>

  <g class="keyboard">
    <rect x="${KB.x}" y="${KB.y}" width="${KB.w}" height="${KB.h}" rx="5" fill="var(--surf)" stroke="var(--line)"/>
${keyMarkup}  </g>

  <!-- ============ the radio in between ============ -->
  <rect class="chip" x="236" y="150" width="38" height="18" rx="3"/>
  <text class="mono bt" x="255" y="163" text-anchor="middle">BT</text>
  <circle class="flow" cx="243" cy="182" r="2.5"/>
  <circle class="flow" cx="255" cy="182" r="2.5" style="animation-delay:.18s"/>
  <circle class="flow" cx="267" cy="182" r="2.5" style="animation-delay:.36s"/>
${reportMarkup}

  <!-- ============ the screen, with nothing of ours on it ============ -->
  <rect class="shell" x="${SC.x}" y="${SC.y}" width="${SC.w}" height="${SC.h}" rx="5"/>
  <line x1="${SC.x}" y1="${SC.y + 28}" x2="${SC.x + SC.w}" y2="${SC.y + 28}" stroke="var(--line)"/>
  <circle class="dot" cx="${SC.x + 14}" cy="${SC.y + 14}" r="3.5"/>
  <circle class="dot" cx="${SC.x + 26}" cy="${SC.y + 14}" r="3.5"/>
  <circle class="dot" cx="${SC.x + 38}" cy="${SC.y + 14}" r="3.5"/>

  <rect x="${BAR.x}" y="${BAR.y}" width="${BAR.w}" height="${BAR.h}" rx="18" fill="var(--surf)" stroke="var(--line)"/>
  <rect class="ring" x="${BAR.x - 1.5}" y="${BAR.y - 1.5}" width="${BAR.w + 3}" height="${BAR.h + 3}" rx="19.5"/>
  <circle cx="${BAR.x + 20}" cy="${BAR.y + 17}" r="6" fill="none" stroke="var(--muted)"/>
  <line x1="${BAR.x + 24.5}" y1="${BAR.y + 21.5}" x2="${BAR.x + 28}" y2="${BAR.y + 25}" stroke="var(--muted)"/>

  <g clip-path="url(#typed)">
    <text class="mono query" x="${TEXT.x}" y="${TEXT.y}">${QUERY}</text>
  </g>
  <rect class="caret" x="${TEXT.x + 1}" y="${TEXT.y - TEXT.size + 2}" width="2" height="${TEXT.size + 2}"/>

  <rect class="hit" x="${BAR.x}" y="140" width="330" height="8" rx="4"/>
  <rect class="hit" x="${BAR.x}" y="164" width="250" height="8" rx="4"/>
  <rect class="hit" x="${BAR.x}" y="188" width="400" height="8" rx="4"/>

  <!-- the group carries the starting point, the path carries the animation:
       a CSS transform would otherwise replace the attribute, not add to it -->
  <g transform="translate(${cursor.from.x},${cursor.from.y})">
    <path class="pointer" d="M0 0 0 20 5.4 14.8 8.9 23 11.9 21.6 8.5 13.6 16 13.3z"/>
  </g>
</svg>
`;

const out = process.argv[2] || __dirname + '/' + "kontroller.svg";
fs.writeFileSync(out, svg);
console.log('wrote ' + out + ' (' + svg.length + ' bytes)');
