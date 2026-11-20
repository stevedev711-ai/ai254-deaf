(function () {
  "use strict";
  const FINGERS = ["Thumb", "Index", "Middle", "Ring", "Little"];
  const $ = id => document.getElementById(id);
  const select = $("sign"), readBtn = $("read"), speakBtn = $("speak");
  const result = $("result"), probsEl = $("probs"), canvas = $("motion");
  let model = null, signs = null, phrase = null, running = false, shown = [];

  const fills = FINGERS.map(name => {
    const el = document.createElement("div");
    el.className = "finger";
    el.innerHTML = '<div class="track"><div class="fill"></div></div><span>' + name + "</span>";
    $("fingers").appendChild(el);
    return el.querySelector(".fill");
  });

  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

  function drawMotion(frames) {
    shown = frames;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = 150;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const g = canvas.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    g.strokeStyle = css("--line"); g.lineWidth = 1;
    g.beginPath(); g.moveTo(0, h / 2); g.lineTo(w, h / 2); g.stroke();
    ["--x", "--y", "--z"].forEach((color, axis) => {
      g.strokeStyle = css(color); g.lineWidth = 2; g.beginPath();
      frames.forEach((f, t) => {
        const v = (f[5 + axis] - (axis === 2 ? 16384 : 0)) / 9000; // remove gravity on the vertical axis
        const x = (t / 39) * (w - 8) + 4, y = h / 2 - v * (h / 2 - 8);
        t ? g.lineTo(x, y) : g.moveTo(x, y);
      });
      g.stroke();
    });
  }

  function drawProbs(probs, best) {
    probsEl.innerHTML = "";
    model.labels.forEach((label, i) => {
      const row = document.createElement("div");
      row.className = "prob" + (probs && i === best && i !== 0 && probs[i] >= model.threshold ? " win" : "");
      const p = probs ? probs[i] : 0;
      row.innerHTML = "<span>" + (label === "_unknown" ? "not a sign" : label) + '</span><span class="bar"><i style="width:' +
        (p * 100).toFixed(1) + '%"></i></span><span class="pct">' + (probs ? Math.round(p * 100) + "%" : "–") + "</span>";
      probsEl.appendChild(row);
    });
  }

  function show(html) { result.innerHTML = html; }

  function read() {
    if (running || !model) return;
    running = true; phrase = null;
    readBtn.disabled = true; speakBtn.disabled = true;
    if (window.speechSynthesis) speechSynthesis.cancel();
    show('<span class="muted">Sign now… reading two seconds of sensor data</span>');
    drawProbs(null);
    const frames = AI254.simulate(signs, select.value);
    const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
    let t = reduced ? 39 : 0;
    const step = () => {
      const f = frames[t];
      fills.forEach((el, i) => { el.style.height = ((1 - f[i] / 4095) / 0.7 * 100).toFixed(1) + "%"; });
      drawMotion(frames.slice(0, t + 1));
      if (++t < 40) return setTimeout(step, 50); // 20 frames per second, like the glove
      const out = AI254.classify(model, frames);
      drawProbs(out.probs, out.best);
      phrase = out.phrase;
      show(phrase ? '<span class="phrase">' + phrase + "</span>" : '<span class="unclear">Sign unclear. Try again.</span>');
      speakBtn.disabled = !phrase || !window.speechSynthesis;
      readBtn.disabled = false; running = false;
    };
    step();
  }

  function speak() {
    if (!phrase || !window.speechSynthesis) return;
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(phrase);
    u.lang = "en";
    speechSynthesis.speak(u);
  }

  readBtn.addEventListener("click", read);
  speakBtn.addEventListener("click", speak);
  select.addEventListener("change", () => { phrase = null; speakBtn.disabled = true; });
  window.addEventListener("resize", () => drawMotion(shown));

  readBtn.disabled = true;
  Promise.all([fetch("model.json").then(r => r.json()), fetch("signs.json").then(r => r.json())])
    .then(([m, s]) => { model = m; signs = s.signs; drawProbs(null); drawMotion([]); readBtn.disabled = false; })
    .catch(() => show('<span class="unclear">The demo model could not be loaded.</span>'));
})();
