const net = require('net');
const { Aedes } = require('aedes');
const aedes = new Aedes();

const port = 1883;
const server = net.createServer(aedes.handle);

server.listen(port, '127.0.0.1', () => {
  console.log(`Local MQTT broker listening on 127.0.0.1:${port}`);
});

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

function shutdown() {
  server.close(() => {
    aedes.close(() => {
      process.exit(0);
    });
  });
}
