// Uso: node render.js entrada.svg=salida.png ...  → PNG de 144 px con fondo transparente.
const fs = require('fs');
const { Resvg } = require('@resvg/resvg-js');
for (const job of process.argv.slice(2)) {
  const i = job.lastIndexOf('=');
  const src = job.slice(0, i), out = job.slice(i + 1);
  const png = new Resvg(fs.readFileSync(src), { fitTo: { mode: 'width', value: 144 } }).render().asPng();
  fs.writeFileSync(out, png);
}
