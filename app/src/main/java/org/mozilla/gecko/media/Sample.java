/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.media;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.os.Parcel;
import android.os.Parcelable;

/** POD carrying input sample data and info */
public final class Sample implements Parcelable {
    public static final Sample EOS =
            new Sample(null, Long.MIN_VALUE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

    /** Same as {@link BufferInfo#presentationTimeUs} */
    public long presentationTimeUs;
    /** Same as {@link BufferInfo#flags} */
    public int flags;
    public byte[] bytes;

    public BufferInfo asBufferInfo() {
        BufferInfo info = new BufferInfo();
        info.offset = 0;
        info.size = bytes == null ? 0 : bytes.length;
        info.presentationTimeUs = presentationTimeUs;
        info.flags = flags;
        return info;
    }

    public Sample(byte[] bytes, long presentationTimeUs, int flags) {
        this.presentationTimeUs = presentationTimeUs;
        this.flags = flags;
        this.bytes = bytes;
    }

    protected Sample(Parcel in) {
        readFromParcel(in);
    }

    public static final Creator<Sample> CREATOR = new Creator<Sample>() {
        @Override
        public Sample createFromParcel(Parcel in) {
            return new Sample(in);
        }

        @Override
        public Sample[] newArray(int size) {
            return new Sample[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel in) {
        presentationTimeUs = in.readLong();
        flags = in.readInt();
        bytes = in.createByteArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(presentationTimeUs);
        dest.writeInt(flags);
        dest.writeByteArray(bytes);
    }

    @Override
    public String toString() {
        if (isEOS()) {
            return "EOS sample";
        } else {
            StringBuilder str = new StringBuilder();
            str.append("{ pts=").append(presentationTimeUs);
            if (bytes != null) {
                str.append(", size=").append(bytes.length);
            }
            str.append(", flags=").append(Integer.toHexString(flags)).append(" }");
            return str.toString();
        }
    }

    public boolean isEOS() {
        return (flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
    }
}
