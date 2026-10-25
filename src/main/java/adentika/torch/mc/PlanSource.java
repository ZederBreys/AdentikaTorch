package adentika.torch.mc;

/**
 * A module that keeps an up-to-date plan (torch-highlight, torch-area). Placement must only use a plan computed
 * from a scan that started after the previous torch was placed, otherwise it could place a now-redundant torch.
 */
public interface PlanSource {
    /** Latest analysis or null. */
    Analysis.Result result();

    /** Id of the scan the current result comes from (scans are numbered from 1). */
    long resultScanId();

    /** Asks for a rescan as soon as possible; returns the id the next scan will get. */
    long requestFreshScan();
}
