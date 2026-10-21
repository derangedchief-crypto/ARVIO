package com.arflix.tv.player.dvmkv;

import static org.junit.Assert.*;

import android.app.Application;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Exercise the actual vendored writeSampleData call site against packaged Media3,
 * not a fake DTS utility. Synthetic headers test extraction, not audio decoding. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE)
public class MatroskaDtsRuntimeTest {
  @Test public void runtimeExposesTheNewFormatAwareApi() throws Exception {
    assertNotNull(DtsUtil.class.getMethod("updateFormatWithDtsHdInfo",
        ExtractorInput.class, int.class, Format.class));
    assertThrows(NoSuchMethodException.class, () -> DtsUtil.class.getMethod(
        "isSampleDtsHd", ExtractorInput.class, int.class));
  }

  @Test public void coreOnlySampleNoLongerThrowsLinkageError() throws Exception {
    checkSample(core(), MimeTypes.AUDIO_DTS);
  }

  @Test public void coreWithExtensionIsIdentifiedAsDtsHd() throws Exception {
    byte[] core = core();
    byte[] extension = extension();
    byte[] sample = Arrays.copyOf(core, core.length + extension.length);
    System.arraycopy(extension, 0, sample, core.length, extension.length);
    checkSample(sample, MimeTypes.AUDIO_DTS_HD);
  }

  @Test public void extensionWithoutCoreIsAlsoIdentifiedAsDtsHd() throws Exception {
    checkSample(extension(), MimeTypes.AUDIO_DTS_HD);
  }

  @Test public void emptyAndSubSyncwordSamplesAreWrittenWithoutFatalProbe() throws Exception {
    for (int size = 0; size < 4; size++) checkSample(new byte[size], MimeTypes.AUDIO_DTS);
  }

  @Test public void incompleteCoreHeadersRetainOriginalFormat() throws Exception {
    for (int size = 4; size < 10; size++) checkSample(Arrays.copyOf(core(), size), MimeTypes.AUDIO_DTS);
  }

  @Test public void unknownSyncwordRetainsOriginalFormatAndPayload() throws Exception {
    checkSample(new byte[16], MimeTypes.AUDIO_DTS);
  }

  @Test public void incompleteExtensionHeaderDoesNotInventHdFormat() throws Exception {
    checkSample(Arrays.copyOf(extension(), 6), MimeTypes.AUDIO_DTS);
  }

  private static void checkSample(byte[] sample, String expectedMime) throws Exception {
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    MatroskaExtractor.Track track = new MatroskaExtractor.Track();
    track.codecId = "A_DTS";
    track.waitingForDtsAnalysis = true;
    track.format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setSampleRate(48000).setChannelCount(6).setLanguage("eng").build();
    String originalLanguage = track.format.language; // Media3 normalizes eng -> en.
    RecordingOutput output = new RecordingOutput();
    track.output = output;
    extractor.init(new ExtractorOutput() {
      @Override public TrackOutput track(int id, int type) { return output; }
      @Override public void endTracks() {}
      @Override public void seekMap(SeekMap seekMap) {}
    });
    ByteArrayInputStream source = new ByteArrayInputStream(sample);
    DefaultExtractorInput input = new DefaultExtractorInput(source::read, 0, sample.length);
    Method write = MatroskaExtractor.class.getDeclaredMethod("writeSampleData",
        ExtractorInput.class, MatroskaExtractor.Track.class, int.class, boolean.class);
    write.setAccessible(true);
    try {
      assertEquals(sample.length, write.invoke(extractor, input, track, sample.length, false));
    } catch (InvocationTargetException e) {
      // Keep LinkageError visible so the pre-fix call site reproduces this report.
      if (e.getCause() instanceof Error) throw (Error) e.getCause();
      throw e;
    }
    assertFalse(track.waitingForDtsAnalysis);
    assertNotNull(output.format);
    assertEquals(expectedMime, output.format.sampleMimeType);
    assertEquals(48000, output.format.sampleRate);
    assertEquals(6, output.format.channelCount);
    assertEquals(originalLanguage, output.format.language);
    assertEquals(sample.length, input.getPosition());
    assertEquals(sample.length, input.getPeekPosition());
    assertArrayEquals(sample, output.bytes.toByteArray());
  }

  private static byte[] core() {
    byte[] sample = new byte[16];
    sample[0] = 0x7f; sample[1] = (byte) 0xfe; sample[2] = (byte) 0x80; sample[3] = 1;
    sample[7] = (byte) 0xf0; // DTS frame size = ((header[7] & 0xf0) >> 4) + 1 = 16.
    return sample;
  }

  private static byte[] extension() {
    // Minimal extension header without optional static metadata (ETSI TS 102 114).
    // The current Media3 API correctly defaults its MIME type to DTS-HD.
    BitWriter bits = new BitWriter(12);
    bits.put(0x64582025, 32); // SYNCEXTSSH
    bits.put(0, 8); // UserDefinedBits
    bits.put(0, 2); // nExtSSIndex
    bits.put(0, 1); // bHeaderSizeType
    bits.put(11, 8); // nuExtSSHeaderSize: 12 bytes
    bits.put(11, 16); // nuExtSSFsize: 12 bytes
    bits.put(0, 1); // bStaticFieldsPresent
    bits.put(11, 16); // nuAssetFsize
    bits.put(0, 9); // nuAssetDescriptFsize
    bits.put(0, 3); // nuAssetIndex
    return bits.data;
  }

  private static final class BitWriter {
    final byte[] data;
    int position;
    BitWriter(int length) { data = new byte[length]; }
    void put(int value, int count) {
      for (int i = count - 1; i >= 0; i--, position++) {
        if (((value >>> i) & 1) != 0) data[position / 8] |= 1 << (7 - position % 8);
      }
    }
  }

  private static final class RecordingOutput implements TrackOutput {
    Format format;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    @Override public void format(Format format) { this.format = format; }
    @Override public int sampleData(DataReader input, int length, boolean allowEof, int part) throws IOException {
      byte[] buffer = new byte[length];
      int read = input.read(buffer, 0, length);
      if (read == -1) {
        if (!allowEof) throw new EOFException();
        return -1;
      }
      bytes.write(buffer, 0, read);
      return read;
    }
    @Override public void sampleData(ParsableByteArray data, int length, int part) {
      bytes.write(data.getData(), data.getPosition(), length);
      data.skipBytes(length);
    }
    @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {}
  }
}
