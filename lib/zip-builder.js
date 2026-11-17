// Zero-dependency PKZIP archive builder (STORE method with standard CRC-32)

const CRC_TABLE = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  CRC_TABLE[i] = c >>> 0;
}

export function crc32(buffer) {
  let crc = 0xffffffff;
  for (let i = 0; i < buffer.length; i++) {
    crc = CRC_TABLE[(crc ^ buffer[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/**
 * Build an uncompressed PKZIP archive from an array of { name, content } entries.
 * @param {Array<{ name: string, content: string | Buffer }>} entries
 * @returns {Buffer}
 */
export function buildZip(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name.replace(/\\/g, '/'), 'utf8');
    const dataBuf = Buffer.isBuffer(entry.content)
      ? entry.content
      : Buffer.from(String(entry.content), 'utf8');
    const crc = crc32(dataBuf);
    const size = dataBuf.length;

    // Local file header (30 bytes + filename)
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0); // signature
    localHeader.writeUInt16LE(20, 4);         // version needed (2.0)
    localHeader.writeUInt16LE(0x0800, 6);     // UTF-8 filename flag
    localHeader.writeUInt16LE(0, 8);          // compression: 0 (STORE)
    localHeader.writeUInt16LE(0, 10);         // mod time
    localHeader.writeUInt16LE(0x5421, 12);    // mod date
    localHeader.writeUInt32LE(crc, 14);       // crc32
    localHeader.writeUInt32LE(size, 18);      // compressed size
    localHeader.writeUInt32LE(size, 22);      // uncompressed size
    localHeader.writeUInt16LE(nameBuf.length, 26); // filename length
    localHeader.writeUInt16LE(0, 28);         // extra field length

    localParts.push(localHeader, nameBuf, dataBuf);

    // Central directory header (46 bytes + filename)
    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0); // signature
    centralHeader.writeUInt16LE(20, 4);         // version made by
    centralHeader.writeUInt16LE(20, 6);         // version needed
    centralHeader.writeUInt16LE(0x0800, 8);     // UTF-8 filename flag
    centralHeader.writeUInt16LE(0, 10);         // compression: 0 (STORE)
    centralHeader.writeUInt16LE(0, 12);         // mod time
    centralHeader.writeUInt16LE(0x5421, 14);    // mod date
    centralHeader.writeUInt32LE(crc, 16);       // crc32
    centralHeader.writeUInt32LE(size, 20);      // compressed size
    centralHeader.writeUInt32LE(size, 24);      // uncompressed size
    centralHeader.writeUInt16LE(nameBuf.length, 28); // filename length
    centralHeader.writeUInt16LE(0, 30);         // extra field length
    centralHeader.writeUInt16LE(0, 32);         // comment length
    centralHeader.writeUInt16LE(0, 34);         // disk number
    centralHeader.writeUInt16LE(0, 36);         // internal attrs
    centralHeader.writeUInt32LE(0, 38);         // external attrs
    centralHeader.writeUInt32LE(offset, 42);    // relative offset of local header

    centralParts.push(centralHeader, nameBuf);
    offset += localHeader.length + nameBuf.length + dataBuf.length;
  }

  const centralDirSize = centralParts.reduce((acc, buf) => acc + buf.length, 0);

  // End of central directory record (22 bytes)
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);           // signature
  eocd.writeUInt16LE(0, 4);                    // disk number
  eocd.writeUInt16LE(0, 6);                    // start disk
  eocd.writeUInt16LE(entries.length, 8);       // entries on disk
  eocd.writeUInt16LE(entries.length, 10);      // total entries
  eocd.writeUInt32LE(centralDirSize, 12);      // central dir size
  eocd.writeUInt32LE(offset, 16);              // central dir offset
  eocd.writeUInt16LE(0, 20);                   // comment length

  return Buffer.concat([...localParts, ...centralParts, eocd]);
}
