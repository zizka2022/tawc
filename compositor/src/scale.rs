use smithay::output::Scale;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct OutputScale {
    fractional: f64,
}

impl OutputScale {
    pub fn new(fractional: f64) -> Self {
        assert!(
            fractional.is_finite() && fractional > 0.0,
            "output scale must be a finite positive value",
        );
        Self { fractional }
    }

    pub fn fractional(self) -> f64 {
        self.fractional
    }

    pub fn integer_fallback(self) -> i32 {
        self.fractional.ceil().max(1.0) as i32
    }

    pub fn smithay_scale(self) -> Scale {
        Scale::Fractional(self.fractional)
    }

    pub fn logical_size(self, physical_w: i32, physical_h: i32) -> (i32, i32) {
        (
            logical_extent(physical_w, self.fractional),
            logical_extent(physical_h, self.fractional),
        )
    }

    pub fn logical_coord(self, physical: f64) -> f64 {
        physical / self.fractional
    }

    /// This scale adjusted for a display whose density is `ratio` times the
    /// phone's (e.g. 160/450 dpi for a DeX monitor). Never drops below 1.0
    /// (or below `self` if that is already under 1.0): sub-1 scales make
    /// text unreadably small on a monitor. Rounded to the 1/120 steps of
    /// wp_fractional_scale_v1.
    pub fn for_density_ratio(self, ratio: f64) -> Self {
        if !ratio.is_finite() || ratio <= 0.0 || ratio == 1.0 {
            return self;
        }
        let scaled = (self.fractional * ratio).max(self.fractional.min(1.0));
        Self::new(((scaled * 120.0).round() / 120.0).max(1.0 / 120.0))
    }
}
