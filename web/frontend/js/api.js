// Server communication.

async function post(url, body) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await resp.json();
  if (!resp.ok || data.error) {
    throw new Error(data.error || `HTTP ${resp.status}`);
  }
  return data;
}

export const api = {
  async compileAndLoad(source) {
    return post('/api/compile-and-load', { source });
  },

  async write(writeRequest) {
    return post('/api/write', writeRequest);
  },

  async read(tableId = 0) {
    const resp = await fetch(`/api/read?table_id=${tableId}`);
    return resp.json();
  },

  async sendPacket(ingressPort, payloadHex) {
    return post('/api/packet', { ingress_port: ingressPort, payload_hex: payloadHex });
  },

  async getPipeline() {
    const resp = await fetch('/api/pipeline');
    return resp.json();
  },
};
