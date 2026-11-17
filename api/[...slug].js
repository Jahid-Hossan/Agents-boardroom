import { requestHandler } from '../server.js';

export default async function handler(req, res) {
  return await requestHandler(req, res);
}
