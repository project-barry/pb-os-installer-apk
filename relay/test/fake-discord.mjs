// Stands in for Discord: logs every webhook post to stdout.
import http from 'node:http';
http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    console.log('DISCORD POST', body);
    res.writeHead(204).end();
  });
}).listen(8799, () => console.log('fake discord on :8799'));
