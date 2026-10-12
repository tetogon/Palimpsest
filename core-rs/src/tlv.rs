//! tag u8 ‖ len LEB128 (≤ 4 bytes, ≤ 2²⁰, canonical) ‖ value (spec §6.3).

use zeroize::Zeroize;

pub const MAX_LEN: usize = 1 << 20;

#[derive(Clone)]
pub struct Field {
    pub tag: u8,
    pub value: Vec<u8>,
}

impl Drop for Field {
    fn drop(&mut self) {
        self.value.zeroize();
    }
}

pub fn parse(buf: &[u8], offset: usize) -> Option<Vec<Field>> {
    let mut fields = Vec::new();
    let mut p = offset;
    while p < buf.len() {
        let tag = buf[p];
        p += 1;
        let mut len = 0usize;
        let mut bytes = 0;
        loop {
            if p >= buf.len() || bytes == 4 {
                return None;
            }
            let b = buf[p];
            p += 1;
            len |= ((b & 0x7F) as usize) << (7 * bytes);
            bytes += 1;
            if b & 0x80 == 0 {
                if bytes > 1 && b == 0 {
                    return None; // overlong encoding
                }
                break;
            }
        }
        if len > MAX_LEN || len > buf.len() - p {
            return None;
        }
        fields.push(Field { tag, value: buf[p..p + len].to_vec() });
        p += len;
    }
    Some(fields)
}

#[derive(Default)]
pub struct Writer {
    out: Vec<u8>,
}

impl Writer {
    pub fn put(&mut self, tag: u8, value: &[u8]) {
        assert!(value.len() <= MAX_LEN);
        self.out.push(tag);
        let mut len = value.len();
        loop {
            let b = (len & 0x7F) as u8;
            len >>= 7;
            if len != 0 {
                self.out.push(b | 0x80);
            } else {
                self.out.push(b);
                break;
            }
        }
        self.out.extend_from_slice(value);
    }

    pub fn put_u8(&mut self, tag: u8, v: u8) {
        self.put(tag, &[v]);
    }

    pub fn put_u32(&mut self, tag: u8, v: u32) {
        self.put(tag, &v.to_le_bytes());
    }

    pub fn put_u64(&mut self, tag: u8, v: u64) {
        self.put(tag, &v.to_le_bytes());
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_overlong_and_truncated() {
        assert!(parse(&[1, 0x80, 0x00], 0).is_none());
        assert!(parse(&[1, 5, 1, 2], 0).is_none());
        assert_eq!(parse(&[1, 2, 7, 7], 0).unwrap().len(), 1);
    }

    #[test]
    fn round_trip_long_value() {
        let mut w = Writer::default();
        w.put(9, &vec![3u8; 300]);
        let f = parse(&w.into_bytes(), 0).unwrap();
        assert_eq!(f[0].tag, 9);
        assert_eq!(f[0].value.len(), 300);
    }
}
