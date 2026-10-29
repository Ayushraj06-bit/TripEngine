import { describe, expect, it } from 'vitest';
import { decode, encode } from './stomp';

describe('STOMP frames', () => {
  it('are written and read back alike', () => {
    const frame = {
      command: 'SEND',
      headers: { destination: '/app/driver/location', 'content-type': 'application/json' },
      body: '{"lat":12.97}',
    };
    expect(encode(frame)).toBe(
      'SEND\ndestination:/app/driver/location\ncontent-type:application/json\n\n{"lat":12.97}\0',
    );
    expect(decode(encode(frame))).toEqual([frame]);
  });

  it('escape what a header cannot hold, except in CONNECT', () => {
    const odd = { command: 'SUBSCRIBE', headers: { id: 'a:b\nc\\d' }, body: '' };
    expect(encode(odd)).toContain('id:a\\cb\\nc\\\\d');
    expect(decode(encode(odd))[0]?.headers['id']).toBe('a:b\nc\\d');
    const connect = { command: 'CONNECT', headers: { Authorization: 'Bearer a:b' }, body: '' };
    expect(encode(connect)).toContain('Authorization:Bearer a:b');
  });

  it('read several frames at once, skipping the heartbeats between them', () => {
    const data = `\n\nCONNECTED\nversion:1.2\n\n\0\nMESSAGE\nsubscription:sub-0\n\n{}\0\n`;
    expect(decode(data).map((f) => f.command)).toEqual(['CONNECTED', 'MESSAGE']);
    expect(decode('\n')).toEqual([]);
  });

  it('keep the first of a repeated header, as the spec says', () => {
    expect(decode('MESSAGE\nfoo:1\nfoo:2\n\n\0')[0]?.headers['foo']).toBe('1');
  });
});
