package com.example.novelreader

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest

// Restricted WOFF2 reader: reads cmap and validates the known glyph structure.
// It does not render or reconstruct TrueType outlines.
internal fun decodeAaaFont(file: ByteArray, glyphs: String): Map<Char, Char> {
    fun need(ok: Boolean) = require(ok) { "字体结构与已验证样本不同，已停止以避免错字。" }
    fun u16(a: ByteArray, p: Int): Int { need(p >= 0 && p + 2 <= a.size); return ((a[p].toInt() and 255) shl 8) or (a[p+1].toInt() and 255) }
    fun u32(a: ByteArray, p: Int): Int { val n = (u16(a,p).toLong() shl 16) or u16(a,p+2).toLong(); need(n <= Int.MAX_VALUE); return n.toInt() }
    need(file.size in 48..8_388_608 && String(file,0,4,Charsets.US_ASCII)=="wOF2")
    need(u32(file,4)==65536 && u32(file,8)==file.size && glyphs.length==6764)
    val count=u16(file,12);need(count in 1..63)
    val compressed=u32(file,20)
    val tags="cmap|head|hhea|hmtx|maxp|name|OS/2|post|cvt |fpgm|glyf|loca|prep|CFF |VORG|EBDT|EBLC|gasp|hdmx|kern|LTSH|PCLT|VDMX|vhea|vmtx|BASE|GDEF|GPOS|GSUB|EBSC|JSTF|MATH|CBDT|CBLC|COLR|CPAL|SVG |sbix|acnt|avar|bdat|bloc|bsln|cvar|fdsc|feat|fmtx|fvar|gvar|hsty|just|lcar|mort|morx|opbd|prop|trak|Zapf|Silf|Glat|Gloc|Feat|Sill".split('|')
    var cursor=48
    fun byte():Int {need(cursor<file.size);return file[cursor++].toInt() and 255}
    fun base128():Int {
        var value=0L
        repeat(5) { i -> val b=byte();need(!(i==0&&b==128));value=value*128+(b and 127);need(value<=8_388_608);if(b and 128==0)return value.toInt() }
        error("字体长度编码异常。")
    }
    data class Table(val start:Int,val length:Int,val transformed:Boolean)
    val tables=linkedMapOf<String,Table>();var total=0
    repeat(count) {
        val flag=byte();val index=flag and 63
        val tag=if(index==63) {need(cursor+4<=file.size);String(file,cursor,4,Charsets.US_ASCII).also{cursor+=4}} else tags[index]
        val version=flag ushr 6;val original=base128()
        val transformed=if(tag=="glyf"||tag=="loca")version==0 else version!=0
        need(if(tag=="glyf"||tag=="loca")version==0||version==3 else version==0||(tag=="hmtx"&&version==1))
        val length=if(transformed)base128() else original
        need(tag !in tables && total.toLong()+length<=8_388_608)
        tables[tag]=Table(total,length,transformed);total+=length
    }
    need(compressed>0 && cursor.toLong()+compressed<=file.size)
    val data=BrotliInputStream(ByteArrayInputStream(file,cursor,compressed)).use { input ->
        val result=ByteArray(total);var p=0
        while(p<total) {val n=input.read(result,p,total-p);need(n>0);p+=n}
        need(input.read()==-1);result
    }
    fun table(name:String):ByteArray {val t=tables[name]?:error("字体缺少必要数据。");return data.copyOfRange(t.start,t.start+t.length)}
    val glyf=table("glyf");need(tables.getValue("glyf").transformed && glyf.size>=36)
    need(u16(glyf,0)==0 && u16(glyf,4)==6764)
    val contours=u32(glyf,8);val points=u32(glyf,12)
    need(contours==13528 && points==72489 && 36+contours+points<=glyf.size)
    val digest=MessageDigest.getInstance("SHA-256").digest(glyf.copyOfRange(36,36+contours+points)).joinToString(""){"%02x".format(it)}
    need(digest=="8ad5bc2944e0969e9c72d0ba5d9fafee0567e514c89726ec85824c8a77480853")
    val cmap=table("cmap");need(!tables.getValue("cmap").transformed && u16(cmap,0)==0)
    var offset=-1
    repeat(u16(cmap,2).also{need(it<=32)}) { i ->
        val p=4+i*8;val platform=u16(cmap,p);val encoding=u16(cmap,p+2);val start=u32(cmap,p+4)
        if((platform==0 || (platform==3&&encoding==1)) && u16(cmap,start)==4)offset=start
    }
    need(offset>=0);val length=u16(cmap,offset+2);need(length>=16&&offset+length<=cmap.size)
    val sub=cmap.copyOfRange(offset,offset+length);val segments=u16(sub,6)/2;need(segments in 1..8192)
    val end=14;val start=end+segments*2+2;val delta=start+segments*2;val range=delta+segments*2
    need(range+segments*2<=sub.size)
    val result=linkedMapOf<Char,Char>();val seen=hashSetOf<Int>();var last=-1
    repeat(segments) { i ->
        val first=u16(sub,start+i*2);val final=u16(sub,end+i*2);need(first<=final&&first>last);last=final
        val shift=u16(sub,delta+i*2);val relative=u16(sub,range+i*2)
        for(c in first..final) {
            if(c==65535)continue
            val gid=if(relative==0)(c+shift) and 65535 else {
                val value=u16(sub,range+i*2+relative+(c-first)*2)
                if(value==0)0 else (value+shift) and 65535
            }
            need(gid in 1 until glyphs.length && seen.add(gid))
            if(glyphs[gid]!='\u0000')result[c.toChar()]=glyphs[gid]
        }
    }
    need(seen.size==6763 && result.size==6763)
    need(result.keys == glyphs.drop(1).toSet() && result.values.toSet() == result.keys)
    return result
}
