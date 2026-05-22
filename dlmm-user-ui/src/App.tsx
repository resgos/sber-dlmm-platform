import { Routes, Route, Navigate } from 'react-router-dom'
import UserLayout from '@/components/UserLayout'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'
import DashboardPage from '@/pages/DashboardPage'
import SwapPage from '@/pages/SwapPage'
import HedgePage from '@/pages/HedgePage'
import PoolsPage from '@/pages/PoolsPage'
import RebalancePage from '@/pages/RebalancePage'
import PoolComparePage from '@/pages/PoolComparePage'
import PoolDetailPage from '@/pages/PoolDetailPage'
import LiquidityPage from '@/pages/LiquidityPage'
import PositionsPage from '@/pages/PositionsPage'
import TransactionsPage from '@/pages/TransactionsPage'
import ProfilePage from '@/pages/ProfilePage'

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/" element={<UserLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="swap" element={<SwapPage />} />
        <Route path="hedge" element={<HedgePage />} />
        <Route path="pools" element={<PoolsPage />} />
        <Route path="pools/:id" element={<PoolDetailPage />} />
        <Route path="pools/:id/liquidity" element={<LiquidityPage />} />
        <Route path="positions" element={<PositionsPage />} />
        <Route path="rebalance" element={<RebalancePage />} />
        <Route path="pools/compare" element={<PoolComparePage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export default App
