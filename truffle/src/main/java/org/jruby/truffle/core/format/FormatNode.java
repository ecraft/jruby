/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.format;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.core.array.ArrayUtils;
import org.jruby.truffle.core.format.exceptions.TooFewArgumentsException;
import org.jruby.truffle.core.rope.CodeRange;
import org.jruby.util.ByteList;

import java.util.Arrays;

/**
 * The root of the pack nodes.
 * <p>
 * Contains methods to change the state of the parser which is stored in the
 * frame.
 */
@ImportStatic(FormatGuards.class)
public abstract class FormatNode extends Node {

    private final RubyContext context;

    private final ConditionProfile writeMoreThanZeroBytes = ConditionProfile.createBinaryProfile();

    public FormatNode(RubyContext context) {
        this.context = context;
    }

    public abstract Object execute(VirtualFrame frame);

    /**
     * Get the length of the source array.
     */
    public int getSourceLength(VirtualFrame frame) {
        try {
            return frame.getInt(FormatFrameDescriptor.SOURCE_LENGTH_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Get the current position we are reading from in the source array.
     */
    protected int getSourcePosition(VirtualFrame frame) {
        try {
            return frame.getInt(FormatFrameDescriptor.SOURCE_POSITION_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Set the current position we will read from next in the source array.
     */
    protected void setSourcePosition(VirtualFrame frame, int position) {
        frame.setInt(FormatFrameDescriptor.SOURCE_POSITION_SLOT, position);
    }

    /**
     * Advanced the position we are reading from in the source array by one
     * element.
     */
    protected int advanceSourcePosition(VirtualFrame frame) {
        return advanceSourcePosition(frame, 1);
    }

    protected int advanceSourcePosition(VirtualFrame frame, int count) {
        final int sourcePosition = getSourcePosition(frame);

        if (sourcePosition + count > getSourceLength(frame)) {
            CompilerDirectives.transferToInterpreter();
            throw new TooFewArgumentsException();
        }

        setSourcePosition(frame, sourcePosition + count);

        return sourcePosition;
    }

    protected int advanceSourcePositionNoThrow(VirtualFrame frame) {
        return advanceSourcePositionNoThrow(frame, 1, false);
    }

    protected int advanceSourcePositionNoThrow(VirtualFrame frame, int count, boolean consumePartial) {
        final int sourcePosition = getSourcePosition(frame);

        final int sourceLength = getSourceLength(frame);

        if (sourcePosition + count > sourceLength) {
            if (consumePartial) {
                setSourcePosition(frame, sourceLength);
            }

            return -1;
        }

        setSourcePosition(frame, sourcePosition + count);

        return sourcePosition;
    }

    /**
     * Get the output array we are writing to.
     */
    protected Object getOutput(VirtualFrame frame) {
        try {
            return frame.getObject(FormatFrameDescriptor.OUTPUT_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Set the output array we are writing to. This should never be used in the
     * compiled code - having to change the output array to resize is is a
     * deoptimizing action.
     */
    protected void setOutput(VirtualFrame frame, Object output) {
        CompilerAsserts.neverPartOfCompilation();
        frame.setObject(FormatFrameDescriptor.OUTPUT_SLOT, output);
    }

    /**
     * Get the current position we are writing to the in the output array.
     */
    protected int getOutputPosition(VirtualFrame frame) {
        try {
            return frame.getInt(FormatFrameDescriptor.OUTPUT_POSITION_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Set the current position we are writing to in the output array.
     */
    protected void setOutputPosition(VirtualFrame frame, int position) {
        frame.setInt(FormatFrameDescriptor.OUTPUT_POSITION_SLOT, position);
    }

    protected int getStringLength(VirtualFrame frame) {
        try {
            return frame.getInt(FormatFrameDescriptor.STRING_LENGTH_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void setStringLength(VirtualFrame frame, int length) {
        frame.setInt(FormatFrameDescriptor.STRING_LENGTH_SLOT, length);
    }

    protected void increaseStringLength(VirtualFrame frame, int additionalLength) {
        setStringLength(frame, getStringLength(frame) + additionalLength);
    }

    protected void setStringCodeRange(VirtualFrame frame, CodeRange codeRange) {
        try {
            final int existingCodeRange = frame.getInt(FormatFrameDescriptor.STRING_CODE_RANGE_SLOT);

            if (codeRange.toInt() > existingCodeRange) {
                frame.setInt(FormatFrameDescriptor.STRING_CODE_RANGE_SLOT, codeRange.toInt());
            }
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Set the output to be tainted.
     */
    protected void setTainted(VirtualFrame frame) {
        frame.setBoolean(FormatFrameDescriptor.TAINT_SLOT, true);
    }

    /**
     * Write a single byte.
     */
    protected void writeByte(VirtualFrame frame, byte value) {
        byte[] output = ensureCapacity(frame, 1);
        final int outputPosition = getOutputPosition(frame);
        output[outputPosition] = value;
        setOutputPosition(frame, outputPosition + 1);
        setStringCodeRange(frame, value >= 0 ? CodeRange.CR_7BIT : CodeRange.CR_VALID);
        increaseStringLength(frame, 1);
    }

    /**
     * Write an array of bytes to the output.
     */
    protected void writeBytes(VirtualFrame frame, byte... values) {
        writeBytes(frame, values, 0, values.length);
    }

    /**
     * Write a {@link ByteList} to the output.
     */
    protected void writeBytes(VirtualFrame frame, ByteList values) {
        writeBytes(frame, values.getUnsafeBytes(), values.begin(), values.length());
    }

    /**
     * Write a range of an array of bytes to the output.
     */
    protected void writeBytes(VirtualFrame frame, byte[] values, int valuesStart, int valuesLength) {
        byte[] output = ensureCapacity(frame, valuesLength);
        final int outputPosition = getOutputPosition(frame);
        System.arraycopy(values, valuesStart, output, outputPosition, valuesLength);
        setOutputPosition(frame, outputPosition + valuesLength);
        increaseStringLength(frame, valuesLength);
    }

    /**
     * Write null bytes to the output.
     */
    protected void writeNullBytes(VirtualFrame frame, int length) {
        if (writeMoreThanZeroBytes.profile(length > 0)) {
            ensureCapacity(frame, length);
            final int outputPosition = getOutputPosition(frame);
            setOutputPosition(frame, outputPosition + length);
            increaseStringLength(frame, length);
        }
    }

    private byte[] ensureCapacity(VirtualFrame frame, int length) {
        byte[] output = (byte[]) getOutput(frame);
        final int outputPosition = getOutputPosition(frame);

        if (outputPosition + length > output.length) {
            // If we ran out of output byte[], deoptimize and next time we'll allocate more

            CompilerDirectives.transferToInterpreterAndInvalidate();
            output = Arrays.copyOf(output, ArrayUtils.capacity(getContext(), output.length, outputPosition + length));
            setOutput(frame, output);
        }

        return output;
    }

    protected RubyContext getContext() {
        return context;
    }

    protected boolean isNil(Object object) {
        return object == context.getCoreLibrary().getNilObject();
    }

}
