/* Ant-style outlined icons — minimal set used across the UI kit.
   All 1em sized, currentColor stroked. Sized via .anticon wrapper.
*/
const I = ({ children, size = 16, ...rest }) => (
  <span className="anticon" style={{ width: size, height: size, display: 'inline-flex' }} {...rest}>
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      {children}
    </svg>
  </span>
);

const HomeOutlined        = (p) => <I {...p}><path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/></I>;
const SwapOutlined        = (p) => <I {...p}><path d="M7 7h13l-3-3"/><path d="M17 17H4l3 3"/></I>;
const SwapVertOutlined    = (p) => <I {...p}><path d="M7 7v13l-3-3"/><path d="M17 17V4l3 3"/></I>;
const FundOutlined        = (p) => <I {...p}><path d="M3 3v18h18"/><path d="M7 17l4-4 4 4 5-7"/></I>;
const PieChartOutlined    = (p) => <I {...p}><path d="M12 3v9l8-3a9 9 0 1 1-8-6z"/></I>;
const TransactionOutlined = (p) => <I {...p}><path d="M21 7H7l3-3"/><path d="M3 17h14l-3 3"/></I>;
const UserOutlined        = (p) => <I {...p}><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></I>;
const TeamOutlined        = (p) => <I {...p}><circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M3 20a6 6 0 0 1 12 0"/><path d="M15 20a4.5 4.5 0 0 1 6 0"/></I>;
const BankOutlined        = (p) => <I {...p}><path d="M3 21h18"/><path d="M5 21V10l7-6 7 6v11"/><path d="M9 21v-6h6v6"/></I>;
const WalletOutlined      = (p) => <I {...p}><rect x="3" y="6" width="18" height="13" rx="2"/><path d="M16 6V4H4v15"/><circle cx="17" cy="13" r="1.2" fill="currentColor"/></I>;
const DollarOutlined      = (p) => <I {...p}><circle cx="12" cy="12" r="9"/><path d="M9 9c0-1.5 1.3-2.5 3-2.5s3 1 3 2.5-3 2-3 4"/><path d="M12 17h.01"/></I>;
const TrophyOutlined      = (p) => <I {...p}><path d="M8 21h8M12 17v4"/><path d="M6 4h12v4a6 6 0 1 1-12 0V4z"/><path d="M6 8H4a3 3 0 0 0 3 3M18 8h2a3 3 0 0 1-3 3"/></I>;
const BarChartOutlined    = (p) => <I {...p}><path d="M7 20V10"/><path d="M12 20V4"/><path d="M17 20v-6"/></I>;
const LineChartOutlined   = (p) => <I {...p}><path d="M3 3v18h18"/><path d="M6 16l4-5 4 3 5-7"/></I>;
const CheckCircleOutlined = (p) => <I {...p}><circle cx="12" cy="12" r="10"/><path d="M8 12l3 3 5-6"/></I>;
const WarningOutlined     = (p) => <I {...p}><path d="M12 3l10 18H2L12 3z"/><path d="M12 10v5M12 18h.01"/></I>;
const InfoOutlined        = (p) => <I {...p}><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></I>;
const CloseCircleOutlined = (p) => <I {...p}><circle cx="12" cy="12" r="10"/><path d="M9 9l6 6M9 15l6-6"/></I>;
const SettingOutlined     = (p) => <I {...p}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9A1.7 1.7 0 0 0 10 3.6V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></I>;
const BellOutlined        = (p) => <I {...p}><path d="M15 17h5l-1.4-1.4A2 2 0 0 1 18 14.2V11a6 6 0 1 0-12 0v3.2c0 .5-.2 1-.6 1.4L4 17h5"/><path d="M9 17a3 3 0 0 0 6 0"/></I>;
const PlusOutlined        = (p) => <I {...p}><path d="M12 5v14M5 12h14"/></I>;
const DeleteOutlined      = (p) => <I {...p}><path d="M3 6h18M8 6V4h8v2M6 6v14h12V6M10 11v6M14 11v6"/></I>;
const PauseCircleOutlined = (p) => <I {...p}><circle cx="12" cy="12" r="10"/><path d="M10 9v6M14 9v6"/></I>;
const PlayCircleOutlined  = (p) => <I {...p}><circle cx="12" cy="12" r="10"/><path d="M10 8.5l6 3.5-6 3.5z"/></I>;
const ArrowLeftOutlined   = (p) => <I {...p}><path d="M19 12H5M12 5l-7 7 7 7"/></I>;
const ArrowRightOutlined  = (p) => <I {...p}><path d="M5 12h14M12 19l7-7-7-7"/></I>;
const LogoutOutlined      = (p) => <I {...p}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5M21 12H9"/></I>;
const SearchOutlined      = (p) => <I {...p}><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></I>;
const MenuFoldOutlined    = (p) => <I {...p}><path d="M3 6h18M3 12h12M3 18h18M19 9l-3 3 3 3"/></I>;
const MenuUnfoldOutlined  = (p) => <I {...p}><path d="M3 6h18M3 12h12M3 18h18M15 9l3 3-3 3"/></I>;
const AimOutlined         = (p) => <I {...p}><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4"/></I>;
const ColumnWidthOutlined = (p) => <I {...p}><path d="M3 6v12M21 6v12"/><path d="M7 12h10M9 9l-2 3 2 3M15 9l2 3-2 3"/></I>;
const CopyOutlined        = (p) => <I {...p}><rect x="9" y="9" width="11" height="11" rx="1.5"/><path d="M5 15V5a1 1 0 0 1 1-1h10"/></I>;
const DownOutlined        = (p) => <I {...p}><path d="M6 9l6 6 6-6"/></I>;
const CheckOutlined       = (p) => <I {...p}><path d="M5 13l4 4 10-12"/></I>;
const TokenOutlined       = (p) => <I {...p}><circle cx="12" cy="12" r="9"/><path d="M12 7v10M9 9l6 6M15 9l-6 6"/></I>;
const SafetyOutlined      = (p) => <I {...p}><path d="M12 3l8 3v6a8 8 0 0 1-8 9 8 8 0 0 1-8-9V6l8-3z"/><path d="M9 12l2 2 4-4"/></I>;

Object.assign(window, {
  Icon: I,
  HomeOutlined, SwapOutlined, SwapVertOutlined, FundOutlined, PieChartOutlined,
  TransactionOutlined, UserOutlined, TeamOutlined, BankOutlined, WalletOutlined,
  DollarOutlined, TrophyOutlined, BarChartOutlined, LineChartOutlined, CheckCircleOutlined,
  WarningOutlined, InfoOutlined, CloseCircleOutlined, SettingOutlined, BellOutlined,
  PlusOutlined, DeleteOutlined, PauseCircleOutlined, PlayCircleOutlined, ArrowLeftOutlined,
  ArrowRightOutlined, LogoutOutlined, SearchOutlined, MenuFoldOutlined, MenuUnfoldOutlined,
  AimOutlined, ColumnWidthOutlined, CopyOutlined, DownOutlined, CheckOutlined,
  TokenOutlined, SafetyOutlined,
});
