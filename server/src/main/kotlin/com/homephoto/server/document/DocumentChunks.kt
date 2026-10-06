package com.homephoto.server.document

/** Budget includes passage prefix, metadata and special tokens. No silent truncation of OCR. */
object DocumentChunks {
    fun split(text: String, tokens: (String) -> Int): List<String> {
        require(text.length <= 110_000)
        val prefix=text.substringBefore("\n본문:").take(120) + "\n"
        require(tokens(prefix)<200) { "문서 제목 토큰이 너무 많습니다." }
        val result=mutableListOf<String>()
        var start=0
        while(start<text.length) {
            var low=start+1; var high=minOf(text.length,start+4000); var end=start
            while(low<=high) {
                val mid=(low+high)/2
                if(tokens(prefix+text.substring(start,mid))<=480) { end=mid; low=mid+1 } else high=mid-1
            }
            require(end>start) { "문서 청크를 나눌 수 없습니다." }
            if(end<text.length && text[end].isLowSurrogate() && end>start+1) end--
            result.add(prefix+text.substring(start,end))
            if(end==text.length) break
            var overlapStart=end
            // Keep at most forty tokens of preceding context, always advancing.
            for(candidate in end-1 downTo maxOf(start+1,end-160)) {
                if(tokens(text.substring(candidate,end))>40) break
                overlapStart=candidate
            }
            start=overlapStart
        }
        return result
    }
}
