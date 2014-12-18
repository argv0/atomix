/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.log;

import net.openhft.chronicle.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Chronicle based log segment.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class ChronicleLogSegment extends AbstractLogger implements LogSegment {
  private static final byte DELETED = 0;
  private static final byte ACTIVE = 1;
  private final ChronicleLog parent;
  private final File base;
  private final File file;
  private final File index;
  private final long segment;
  private Chronicle chronicle;
  private Excerpt excerpt;
  private ExcerptAppender appender;
  private ExcerptTailer tailer;
  private Long firstIndex;
  private Long lastIndex;
  private int size;

  ChronicleLogSegment(ChronicleLog parent, long segment) {
    this.parent = parent;
    this.base = new File(parent.base().getParent(), String.format("%s-%d", parent.base().getName(), segment));
    this.file = new File(parent.base().getParent(), String.format("%s-%d.log", parent.base().getName(), segment));
    this.index = new File(parent.base().getParent(), String.format("%s-%d.index", parent.base().getName(), segment));
    this.segment = segment;
  }

  @Override
  public Log log() {
    return parent;
  }

  @Override
  public File file() {
    return file;
  }

  @Override
  public File index() {
    return index;
  }

  @Override
  public long segment() {
    return segment;
  }

  @Override
  public long timestamp() {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
      return attributes.creationTime().toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  @Override
  public void open() {
    assertIsNotOpen();
    try {
      chronicle = new IndexedChronicle(base.getAbsolutePath());
      excerpt = chronicle.createExcerpt();
      appender = chronicle.createAppender();
      tailer = chronicle.createTailer();
      if (chronicle.size() > 0) {
        firstIndex = segment;
      }
      try (ExcerptTailer t = tailer.toStart()) {
        long index = t.readLong();
        if (firstIndex == null) {
          firstIndex = index;
        }
        lastIndex = index;
      }
    } catch (IOException e) {
      throw new LogException(e);
    }
  }

  @Override
  public boolean isOpen() {
    return chronicle != null;
  }

  @Override
  public long size() {
    assertIsOpen();
    return chronicle.size();
  }

  @Override
  public boolean isEmpty() {
    return size() > 0;
  }

  @Override
  public long appendEntry(ByteBuffer entry) {
    assertIsOpen();
    long index = lastIndex + 1;
    appender.startExcerpt();
    appender.writeLong(index);
    appender.writeByte(ACTIVE);
    appender.writeInt(entry.limit());
    appender.write(entry);
    appender.finish();
    lastIndex = index;
    size += entry.capacity() + 13; // 13 bytes for index, status, and length
    if (firstIndex == null) {
      firstIndex = segment;
    }
    if (lastIndex == null) {
      lastIndex = segment;
    }
    return index;
  }

  @Override
  public List<Long> appendEntries(List<ByteBuffer> entries) {
    assertIsOpen();
    List<Long> indices = new ArrayList<>(entries.size());
    for (ByteBuffer entry : entries) {
      indices.add(appendEntry(entry));
    }
    return indices;
  }

  @Override
  public Long firstIndex() {
    assertIsOpen();
    return firstIndex;
  }

  @Override
  public Long lastIndex() {
    assertIsOpen();
    return lastIndex;
  }

  @Override
  public boolean containsIndex(long index) {
    assertIsOpen();
    return firstIndex != null && firstIndex <= index && index <= lastIndex;
  }

  @Override
  public ByteBuffer getEntry(long index) {
    assertIsOpen();
    assertContainsIndex(index);
    if (tailer.index(index - segment)) {
      do {
        ByteBuffer entry = extractEntry(tailer, index);
        if (entry != null) {
          return entry;
        }
      } while (tailer.nextIndex());
    }
    return null;
  }

  @Override
  public List<ByteBuffer> getEntries(long from, long to) {
    assertIsOpen();
    assertContainsIndex(from);
    assertContainsIndex(to);
    List<ByteBuffer> entries = new ArrayList<>((int) (to - from + 1));
    long currentIndex = from;
    if (tailer.index(from - segment)) {
      do {
        ByteBuffer entry = extractEntry(tailer, currentIndex);
        if (entry != null) {
          entries.add(entry);
          currentIndex++;
        }
        if (currentIndex > to) {
          return entries;
        }
      } while (tailer.nextIndex());
    }
    return entries;
  }

  /**
   * Extracts an entry from the excerpt.
   */
  private ByteBuffer extractEntry(ExcerptTailer excerpt, long matchIndex) {
    long index = excerpt.readLong();
    byte status = excerpt.readByte();
    if (status == DELETED)
      return null;
    if (index == matchIndex && status == ACTIVE) {
      int length = excerpt.readInt();
      ByteBuffer buffer = ByteBuffer.allocate(length);
      excerpt.read(buffer);
      return buffer;
    } else if (index > matchIndex) {
      throw new IllegalStateException("Log missing entries");
    }
    return null;
  }

  @Override
  public void removeAfter(long index) {
    assertIsOpen();
    if (index < segment) {
      chronicle.clear();
    } else if (excerpt.index(index - segment)) {
      while (excerpt.nextIndex()) {
        if (excerpt.readLong() > index) {
          excerpt.writeByte(DELETED);
        }
      }
    }
    lastIndex = index;
  }

  @Override
  public void compact(long index) {
    compact(index, null);
  }

  @Override
  public void compact(long index, ByteBuffer entry) {
    assertIsOpen();
    assertContainsIndex(index);

    if (index > firstIndex) {
      // Create a new log file using the most recent timestamp.
      File tempBaseFile = new File(base.getParent(), String.format("%s.tmp", base.getName()));
      File tempLogFile = new File(base.getParent(), String.format("%s.tmp.log", base.getName()));
      File tempIndexFile = new File(base.getParent(), String.format("%s.tmp.index", base.getName()));
      int newSize = 0;

      // Create a new chronicle for the new log file.
      try (Chronicle chronicle = new IndexedChronicle(tempBaseFile.getAbsolutePath());
        ExcerptAppender appender = chronicle.createAppender()) {

        // If an entry is to replace the existing entry at the given index, write the new entry
        // first.
        if (entry != null) {
          appender.startExcerpt();
          appender.writeLong(index);
          appender.writeByte(ACTIVE);
          appender.writeInt(entry.limit());
          appender.write(entry);
          appender.finish();
          newSize += entry.limit() + 13; // 13 bytes for index, status, and length
        }

        // Iterate through entries greater than the given index and copy them to the new chronicle.
        long currentIndex = index - segment;
        if (tailer.index(currentIndex)) {
          do {
            ByteBuffer currentEntry = extractEntry(tailer, currentIndex);
            if (currentEntry != null) {
              appender.startExcerpt();
              appender.writeLong(currentIndex);
              appender.writeByte(ACTIVE);
              appender.writeInt(currentEntry.limit());
              appender.write(currentEntry);
              appender.finish();
              newSize += currentEntry.limit() + 13; // 13 bytes for index, status, and length
              currentIndex++;
            }
          } while (tailer.nextIndex());
        }

        // Close the existing chronicle.
        this.excerpt.close();
        this.appender.close();
        this.tailer.close();
        this.chronicle.close();

        // First, create a copy of the existing log files. This can be used to restore the logs
        // during recovery if the compaction fails.
        File historyLogFile = new File(base.getParent(), String.format("%s.history.log",
          base.getName()));
        File historyIndexFile = new File(base.getParent(), String.format("%s.history.index",
          base.getName()));
        Files.copy(file().toPath(), historyLogFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(index().toPath(), historyIndexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // Now rename temporary log files.
        Files.move(tempLogFile.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tempIndexFile.toPath(), index().toPath(), StandardCopyOption.REPLACE_EXISTING);

        // Delete the history files if we've made it this far.
        historyLogFile.delete();
        historyIndexFile.delete();

        // Reset chronicle log types.
        this.chronicle = new IndexedChronicle(file.getAbsolutePath());
        this.excerpt = chronicle.createExcerpt();
        this.appender = chronicle.createAppender();
        this.tailer = chronicle.createTailer();
        this.firstIndex = index;
        this.size = newSize;
      } catch (IOException e) {
        throw new LogException(e);
      }
    }
  }

  @Override
  public void flush() {
    flush(false);
  }

  @Override
  public void flush(boolean force) {
    assertIsOpen();
    if (force || parent.config.isFlushOnWrite()) {
      excerpt.flush();
      appender.flush();
      tailer.flush();
    }
  }

  @Override
  public void close() {
    assertIsOpen();
    try {
      chronicle.close();
    } catch (IOException e) {
      throw new LogException(e);
    } finally {
      chronicle = null;
      excerpt = null;
      firstIndex = null;
      lastIndex = null;
    }
  }

  @Override
  public boolean isClosed() {
    return chronicle == null;
  }

  @Override
  public void delete() {
    file.delete();
    index.delete();
    parent.deleteSegment(segment);
  }

}