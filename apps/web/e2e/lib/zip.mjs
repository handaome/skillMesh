// 手搓一个最小 ZIP（store 模式，不压缩）——node 没有内置 zip，
// 而 E2E 要往服务端传真包才能进审核队列。
//
// ZIP 格式：本地文件头 + 数据 + 中央目录 + 结束记录。CRC32 用标准多项式算。

const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let i = 0; i < 256; i++) {
    let c = i
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1
    table[i] = c >>> 0
  }
  return table
})()

function crc32(buf) {
  let crc = 0xFFFFFFFF
  for (const b of buf) crc = CRC_TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8)
  return (crc ^ 0xFFFFFFFF) >>> 0
}

/** DOS 格式的时间戳（秒级精度够用）。 */
function dosTime(date) {
  const time = ((date.getHours() & 0x1f) << 11) | ((date.getMinutes() & 0x3f) << 5)
  const day = (((date.getFullYear() - 1980) & 0x7f) << 9) | (((date.getMonth() + 1) & 0xf) << 5)
    | (date.getDate() & 0x1f)
  return { time, day }
}

/**
 * 把若干文件打成一个 zip。
 * @param {{path: string, content: Buffer}[]} files
 * @returns {Buffer}
 */
export function zipFiles(files) {
  const now = new Date()
  const { time, day } = dosTime(now)
  const chunks = []
  const central = []
  let offset = 0

  for (const file of files) {
    const name = Buffer.from(file.path, 'utf8')
    const data = file.content
    const crc = crc32(data)

    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)      // 解压所需版本
    local.writeUInt16LE(0, 6)       // 标志位
    local.writeUInt16LE(0, 8)       // 压缩方式：0 = store
    local.writeUInt16LE(time, 10)
    local.writeUInt16LE(day, 12)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(name.length, 26)
    local.writeUInt16LE(0, 28)      // extra 长度

    chunks.push(local, name, data)

    const header = Buffer.alloc(46)
    header.writeUInt32LE(0x02014b50, 0)
    header.writeUInt16LE(20, 4)     // 制作者版本
    header.writeUInt16LE(20, 6)     // 解压所需版本
    header.writeUInt16LE(0, 8)
    header.writeUInt16LE(0, 10)
    header.writeUInt16LE(time, 12)
    header.writeUInt16LE(day, 14)
    header.writeUInt32LE(crc, 16)
    header.writeUInt32LE(data.length, 20)
    header.writeUInt32LE(data.length, 24)
    header.writeUInt16LE(name.length, 28)
    header.writeUInt16LE(0, 30)    // extra
    header.writeUInt16LE(0, 32)    // comment
    header.writeUInt16LE(0, 34)    // 起始盘
    header.writeUInt16LE(0, 36)    // 内部属性
    header.writeUInt32LE(0, 38)    // 外部属性
    header.writeUInt32LE(offset, 42)
    central.push(header, name)

    offset += local.length + name.length + data.length
  }

  const centralBuf = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(0, 4)
  end.writeUInt16LE(0, 6)
  end.writeUInt16LE(files.length, 8)
  end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(centralBuf.length, 12)
  end.writeUInt32LE(offset, 16)
  end.writeUInt16LE(0, 20)

  return Buffer.concat([...chunks, centralBuf, end])
}
