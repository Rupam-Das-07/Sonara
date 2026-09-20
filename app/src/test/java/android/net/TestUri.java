package android.net;

import java.util.Collections;
import java.util.List;

public class TestUri extends Uri {
    public TestUri() {
        super();
    }

    @Override
    public boolean isHierarchical() {
        return false;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String getScheme() {
        return "content";
    }

    @Override
    public String getSchemeSpecificPart() {
        return "";
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return "";
    }

    @Override
    public String getAuthority() {
        return "";
    }

    @Override
    public String getEncodedAuthority() {
        return "";
    }

    @Override
    public String getUserInfo() {
        return "";
    }

    @Override
    public String getEncodedUserInfo() {
        return "";
    }

    @Override
    public String getHost() {
        return "";
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return "";
    }

    @Override
    public String getEncodedPath() {
        return "";
    }

    @Override
    public String getQuery() {
        return "";
    }

    @Override
    public String getEncodedQuery() {
        return "";
    }

    @Override
    public String getFragment() {
        return "";
    }

    @Override
    public String getEncodedFragment() {
        return "";
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        return "";
    }

    @Override
    public Builder buildUpon() {
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {}

    @Override
    public int compareTo(Uri o) {
        return 0;
    }

    @Override
    public String toString() {
        return "content://test";
    }
}
