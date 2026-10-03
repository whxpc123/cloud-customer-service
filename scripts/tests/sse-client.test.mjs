/** 直接验证生产分帧器，网络块边界按 UTF-8 字节拆分；这些测试不访问模型。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {consumeSse,createAnswerTracker} from '../../src/main/resources/static/sse-client.mjs';
const enc=new TextEncoder();
const bytes=text=>enc.encode(text);
const body=chunks=>new ReadableStream({start(c){chunks.forEach(x=>c.enqueue(x));c.close();}});
const wire=': ping\r\nid: abc\r\nevent: answer.delta\r\ndata: {"text":"你好🙂"}\r\n\r\nevent: done\ndata: 第一行\ndata: 第二行\n\n';
const expected=[['answer.delta','{"text":"你好🙂"}',{id:'abc'}],['done','第一行\n第二行',{id:''}]];
const encoded=bytes(wire);
for(let index=1;index<encoded.length;index++)test(`UTF-8 / CRLF 网络切点 ${index}`,async()=>{
  const events=[];await consumeSse(body([encoded.slice(0,index),encoded.slice(index)]),(...x)=>events.push(x));assert.deepEqual(events,expected);
});
test('每字节一块与全部事件粘包一致',async()=>{for(const chunks of [[encoded],[...encoded].map(x=>new Uint8Array([x]))]){const events=[];await consumeSse(body(chunks),(...x)=>events.push(x));assert.deepEqual(events,expected);}});
test('CR、空 data、注释、不完整尾部',async()=>{const events=[];await consumeSse(body([bytes(':ping\r\rdata:\r\rdata:  空格 \r\rdata: 不应派发')]),(...x)=>events.push(x));assert.deepEqual(events.map(x=>x[1]),['',' 空格 ']);});
test('超大行和多行累积帧均拒绝',async()=>{for(const wire of ['data: '+ 'x'.repeat(100),'data: 12345\ndata: 12345\ndata: 12345\n\n',('data:\n').repeat(20)])await assert.rejects(consumeSse(body([bytes(wire)]),()=>{},25),/过大/);});
test('无效 UTF-8 及空 body 拒绝',async()=>{await assert.rejects(consumeSse(body([new Uint8Array([0xff])]),()=>{}));await assert.rejects(consumeSse(null,()=>{}),/没有/);});
test('回调错误取消底层并释放 reader',async()=>{let cancelled=false;const stream=new ReadableStream({start(c){c.enqueue(bytes('data: x\n\n'));},cancel(){cancelled=true;}});await assert.rejects(consumeSse(stream,()=>{throw new Error('bad event');}),/bad event/);assert.equal(cancelled,true);assert.equal(stream.locked,false);});
const id='12345678-1234-1234-1234-123456789abc';
const packet=(sequence,text='',turnId=id)=>JSON.stringify({sequence,text,turnId});
test('草稿逐段可见，保留空白，不把 EOF 当成完成',()=>{const text=[];const t=createAnswerTracker(x=>text.push(x));t.accept('turn.started',packet(1));t.accept('answer.delta',packet(2,'你好'));t.accept('answer.delta',packet(3,' \n'));assert.deepEqual(text,['你好',' \n']);assert.equal(t.snapshot().terminal,false);t.accept('turn.completed',packet(4));assert.equal(t.snapshot().status,'completed');});
test('失败保留已收到草稿，序号重复不重复展示',()=>{const text=[];const t=createAnswerTracker(x=>text.push(x));t.accept('turn.started',packet(1));t.accept('answer.delta',packet(2,'x'));t.accept('answer.delta',packet(2,'x'));t.accept('turn.failed',packet(3));assert.equal(text.join(''),'x');assert.equal(t.snapshot().status,'failed');assert.equal(t.snapshot().terminal,true);});
test('跨轮次、缺号、未知类型、终结后继续均拒绝',()=>{for(const action of [t=>t.accept('answer.delta',packet(2,'','00000000-0000-0000-0000-000000000000')),t=>t.accept('answer.delta',packet(3)),t=>t.accept('bogus',packet(2)),t=>{t.accept('turn.completed',packet(2));t.accept('answer.delta',packet(3));},t=>t.accept('turn.started',packet(2))]){const t=createAnswerTracker();t.accept('turn.started',packet(1));assert.throws(()=>action(t));}});
test('非法结构和缺失开始事件拒绝',()=>{for(const raw of ['null','{}','broken',packet(1.5),packet(0),JSON.stringify({turnId:id,sequence:1,text:42})])assert.throws(()=>createAnswerTracker().accept('turn.started',raw));assert.throws(()=>createAnswerTracker().accept('answer.delta',packet(1)));});
