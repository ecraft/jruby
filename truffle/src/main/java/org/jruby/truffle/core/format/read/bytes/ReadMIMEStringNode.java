/*
 * Copyright (c) 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.format.read.bytes;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.jcodings.specific.ASCIIEncoding;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.core.Layouts;
import org.jruby.truffle.core.format.FormatNode;
import org.jruby.truffle.core.format.read.SourceNode;
import org.jruby.truffle.core.string.StringOperations;
import org.jruby.util.ByteList;
import org.jruby.util.Pack;
import org.jruby.util.StringSupport;

import java.nio.ByteBuffer;

@NodeChildren({
        @NodeChild(value = "value", type = SourceNode.class),
})
public abstract class ReadMIMEStringNode extends FormatNode {

    public ReadMIMEStringNode(RubyContext context) {
        super(context);
    }

    @Specialization
    public Object read(VirtualFrame frame, byte[] source) {
        // Bit string logic copied from jruby.util.Pack - see copyright and authorship there

        final ByteBuffer encode = ByteBuffer.wrap(source, getSourcePosition(frame), getSourceLength(frame) - getSourcePosition(frame));

        byte[] lElem = new byte[Math.max(encode.remaining(),0)];
        int index = 0;
        for(;;) {
            if (!encode.hasRemaining()) break;
            int c = Pack.safeGet(encode);
            if (c != '=') {
                lElem[index++] = (byte)c;
            } else {
                if (!encode.hasRemaining()) break;
                encode.mark();
                int c1 = Pack.safeGet(encode);
                if (c1 == '\n' || (c1 == '\r' && (c1 = Pack.safeGet(encode)) == '\n')) continue;
                int d1 = Character.digit(c1, 16);
                if (d1 == -1) {
                    encode.reset();
                    break;
                }
                encode.mark();
                if (!encode.hasRemaining()) break;
                int c2 = Pack.safeGet(encode);
                int d2 = Character.digit(c2, 16);
                if (d2 == -1) {
                    encode.reset();
                    break;
                }
                byte value = (byte)(d1 << 4 | d2);
                lElem[index++] = value;
            }
        }

        final ByteList result = new ByteList(lElem, 0, index, ASCIIEncoding.INSTANCE, false);
        setSourcePosition(frame, encode.position());

        return Layouts.STRING.createString(getContext().getCoreLibrary().getStringFactory(), StringOperations.ropeFromByteList(result, StringSupport.CR_UNKNOWN));
    }

}
