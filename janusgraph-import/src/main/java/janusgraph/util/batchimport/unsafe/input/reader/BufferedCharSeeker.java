package janusgraph.util.batchimport.unsafe.input.reader;


import janusgraph.util.batchimport.unsafe.helps.InputException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import static java.lang.String.format;

/**
 * Much like a {@link BufferedReader} for a {@link Reader}.
 */
public class BufferedCharSeeker implements CharSeeker
{
    private static final char EOL_CHAR = '\n';
    private static final char EOL_CHAR_2 = '\r';
    private static final char EOF_CHAR = (char) -1;
    private static final char BACK_SLASH = '\\';
    private static final char WHITESPACE = ' ';

    private char[] buffer;
    private int dataLength;
    private int dataCapacity;

    // index into the buffer character array to read the next time nextChar() is called
    private int bufferPos;
    private int bufferStartPos;
    // last index (effectively length) of characters in use in the buffer
    private int bufferEnd;
    // bufferPos denoting the start of this current line that we're reading
    private int lineStartPos;
    // bufferPos when we started reading the current field
    private int seekStartPos;
    // 1-based value of which logical line we're reading a.t.m.
    private int lineNumber;
    // flag to know if we've read to the end
    private boolean eof;
    // char to recognize as quote start/end
    private final char quoteChar;
    // this absolute position + bufferPos is the current position in the source we're reading
    private long absoluteBufferStartPosition;
    private String sourceDescription;
    private final boolean multilineFields;
    private final boolean legacyStyleQuoting;
    private final Source source;
    private Source.Chunk currentChunk;
    private final boolean trim;

    public BufferedCharSeeker(Source source, Configuration config )
    {
        this.source = source;
        this.quoteChar = config.quotationCharacter();
        this.lineStartPos = this.bufferPos;
        this.multilineFields = config.multilineFields();
        this.legacyStyleQuoting = config.legacyStyleQuoting();
        this.trim = getTrimStringIgnoreErrors( config );
    }

    @Override
    public boolean seek( Mark mark, int untilChar ) throws IOException
    {
        if ( eof )
        {   // We're at the end
            return eof( mark );
        }

        // Keep a start position in case we need to further fill the buffer in nextChar, a value can at maximum be the
        // whole buffer, so max one fill per value is supported.
        seekStartPos = bufferPos; // seekStartPos updated in nextChar if buffer flips over, that's why it's a member
        int ch;
        int endOffset = 1;
        int skippedChars = 0;
        int quoteDepth = 0;
        int quoteStartLine = 0;
        boolean isQuoted = false;

        while ( !eof )
        {
            ch = nextChar( skippedChars );
            if ( quoteDepth == 0 )
            {   // In normal mode, i.e. not within quotes
                if ( isWhitespace( ch ) && trim )
                {
                    if ( seekStartPos == bufferPos - 1/* -1 since we just advanced one */ )
                    {
                        // We found a whitespace, which was the first of the value and we've been told to trim that off
                        seekStartPos++;
                    }
                }
                else if ( ch == quoteChar && seekStartPos == bufferPos - 1/* -1 since we just advanced one */ )
                {   // We found a quote, which was the first of the value, skip it and switch mode
                    quoteDepth++;
                    isQuoted = true;
                    seekStartPos++;
                    quoteStartLine = lineNumber;
                }
                else if ( isNewLine( ch ) )
                {   // Encountered newline, done for now
                    if ( bufferPos - 1 == lineStartPos )
                    {   // We're at the start of this read so just skip it
                        seekStartPos++;
                        lineStartPos++;
                        continue;
                    }
                    break;
                }
                else if ( ch == untilChar )
                {   // We found a delimiter, set marker and return true
                    return setMark( mark, endOffset, skippedChars, ch, isQuoted );
                }
                else
                {   // This is a character to include as part of the current value
                    if ( isQuoted )
                    {   // This value is quoted, i.e. started with a quote and has also seen a quote
                        try {
                            throw new InputException(
                                    new String( buffer, seekStartPos, bufferPos - seekStartPos ) );
                        } catch (InputException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            else
            {   // In quoted mode, i.e. within quotes
                if ( ch == quoteChar )
                {   // Found a quote within a quote, peek at next char
                    int nextCh = peekChar( skippedChars );
                    if ( nextCh == quoteChar )
                    {   // Found a double quote, skip it and we're going down one more quote depth (quote-in-quote)
                        repositionChar( bufferPos++, ++skippedChars );
                    }
                    else
                    {   // Found an ending quote, skip it and switch mode
                        endOffset++;
                        quoteDepth--;
                    }
                }
                else if ( isNewLine( ch ) )
                {   // Found a new line inside a quotation...
                    if ( !multilineFields )
                    {   // ...but we are configured to disallow it
                        try {
                            throw new InputException( "IllegalMultilineField" );
                        } catch (InputException e) {
                            e.printStackTrace();
                        }
                    }
                    // ... it's OK, just keep going
                    if ( ch == EOL_CHAR )
                    {
                        lineNumber++;
                    }
                }
                else if ( ch == BACK_SLASH && legacyStyleQuoting )
                {   // Legacy concern, support java style quote encoding
                    int nextCh = peekChar( skippedChars );
                    if ( nextCh == quoteChar || nextCh == BACK_SLASH )
                    {   // Found a slash encoded quote
                        repositionChar( bufferPos++, ++skippedChars );
                    }
                }
                else if ( eof )
                {
                    // We have an open quote but have reached the end of the file, this is a formatting error
                    try {
                        throw new InputException( "MissingEndQuote" );
                    } catch (InputException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        int valueLength = bufferPos - seekStartPos - 1;
        if ( eof && valueLength == 0 && seekStartPos == lineStartPos )
        {   // We didn't find any of the characters sought for
            return eof( mark );
        }

        // We found the last value of the line or stream
        lineNumber++;
        lineStartPos = bufferPos;
        return setMark( mark, endOffset, skippedChars, Mark.END_OF_LINE_CHARACTER, isQuoted );
    }

    private boolean setMark( Mark mark, int endOffset, int skippedChars, int ch, boolean isQuoted )
    {
        int pos = (trim ? rtrim( bufferPos ) : bufferPos) - endOffset - skippedChars;
        mark.set( seekStartPos, pos, ch, isQuoted );
        return true;
    }

    private int rtrim( int start )
    {
        int index = start;
        while ( isWhitespace( buffer[index - 1 /*bufferPos has advanced*/ - 1 /*don't check the last read char (delim or EOF)*/] ) )
        {
            index--;
        }
        return index;
    }

    private boolean isWhitespace( int ch )
    {
        return ch == WHITESPACE;
    }

    private void repositionChar( int offset, int stepsBack )
    {
        // We reposition characters because we might have skipped some along the way, double-quotes and what not.
        // We want to take an as little hit as possible for that, so we reposition each character as long as
        // we're still reading the same value. All other values will not have to take any hit of skipped chars
        // for this particular value.
        buffer[offset - stepsBack] = buffer[offset];
    }

    private boolean isNewLine( int ch )
    {
        return ch == EOL_CHAR || ch == EOL_CHAR_2;
    }

    private int peekChar( int skippedChars ) throws IOException
    {
        int ch = nextChar( skippedChars );
        try
        {
            return ch;
        }
        finally
        {
            if ( ch != EOF_CHAR )
            {
                bufferPos--;
            }
        }
    }

    private boolean eof( Mark mark )
    {
        mark.set( -1, -1, Mark.END_OF_LINE_CHARACTER, false );
        return false;
    }

    private static boolean getTrimStringIgnoreErrors( Configuration config )
    {
        try
        {
            return config.trimStrings();
        }
        catch ( Throwable t )
        {
            // Cypher compatibility can result in older Cypher 2.3 code being passed here with older implementations of
            // Configuration. So we need to ignore the fact that those implementations do not include trimStrings().
            return Configuration.DEFAULT.trimStrings();
        }
    }

    @Override
    public <EXTRACTOR extends Extractor<?>> EXTRACTOR extract( Mark mark, EXTRACTOR extractor )
    {
        if ( !tryExtract( mark, extractor ) )
        {
            throw new IllegalStateException( extractor + " didn't extract value for " + mark +
                    ". For values which are optional please use tryExtract method instead" );
        }
        return extractor;
    }

    @Override
    public boolean tryExtract( Mark mark, Extractor<?> extractor )
    {
        int from = mark.startPosition();
        int to = mark.position();
        return extractor.extract( buffer, from, to - from, mark.isQuoted() );
    }

    private int nextChar( int skippedChars ) throws IOException
    {
        int ch;
        if ( bufferPos < bufferEnd || fillBuffer() )
        {
            ch = buffer[bufferPos];
        }
        else
        {
            ch = EOF_CHAR;
            eof = true;
        }

        if ( skippedChars > 0 )
        {
            repositionChar( bufferPos, skippedChars );
        }
        bufferPos++;
        return ch;
    }

    /**
     * @return {@code true} if something was read, otherwise {@code false} which means that we reached EOF.
     */
    private boolean fillBuffer() throws IOException
    {
        boolean first = currentChunk == null;

        if ( !first )
        {
            if ( bufferPos - seekStartPos >= dataCapacity )
            {
                throw new IllegalStateException( "Tried to read a field larger than buffer size " +
                        dataLength + ". A common cause of this is that a field has an unterminated " +
                        "quote and so will try to seek until the next quote, which ever line it may be on." +
                        " This should not happen if multi-line fields are disabled, given that the fields contains " +
                        "no new-line characters. This field started at " + sourceDescription() + ":" + lineNumber() );
            }
        }

        absoluteBufferStartPosition += dataLength;

        // Fill the buffer with new characters
        Source.Chunk nextChunk = source.nextChunk( first ? -1 : seekStartPos );
        if ( nextChunk == Source.EMPTY_CHUNK )
        {
            return false;
        }

        buffer = nextChunk.data();
        dataLength = nextChunk.length();
        dataCapacity = nextChunk.maxFieldSize();
        bufferPos = nextChunk.startPosition();
        bufferStartPos = bufferPos;
        bufferEnd = bufferPos + dataLength;
        int shift = seekStartPos - nextChunk.backPosition();
        seekStartPos = nextChunk.backPosition();
        if ( first )
        {
            lineStartPos = seekStartPos;
        }
        else
        {
            lineStartPos -= shift;
        }
        String sourceDescriptionAfterRead = nextChunk.sourceDescription();
        if ( !sourceDescriptionAfterRead.equals( sourceDescription ) )
        {   // We moved over to a new source, reset line number
            lineNumber = 0;
            sourceDescription = sourceDescriptionAfterRead;
        }
        currentChunk = nextChunk;
        return dataLength > 0;
    }

    @Override
    public void close() throws IOException
    {
        source.close();
    }

    @Override
    public long position()
    {
        return absoluteBufferStartPosition + (bufferPos - bufferStartPos);
    }

    @Override
    public String sourceDescription()
    {
        return sourceDescription;
    }

    public long lineNumber()
    {
        return lineNumber;
    }

    @Override
    public String toString()
    {
        return format( "%s[source:%s, position:%d, line:%d]", getClass().getSimpleName(),
                sourceDescription(), position(), lineNumber() );
    }

    public static boolean isEolChar( char c )
    {
        return c == EOL_CHAR || c == EOL_CHAR_2;
    }
}